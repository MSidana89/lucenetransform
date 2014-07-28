/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.lucene.store.transform;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.transform.algorithm.ReadDataTransformer;

/**
 * Transparent file read transformation. Since file has log based structure,
 * seek and write are just appended, chunks are merged on read request. Chunk
 * can be overwritten multiple times. Chunks are sorted by position when opening
 * file, to improve seek/read performance. Chunk directory is loaded into
 * memory.
 *
 * @author Mitja Lenič
 */
public class TransformedIndexInput extends IndexInput {

    private long offset = 0;
    private long maxLen = -1;
    /**
     * compressed input
     *
     */
    private IndexInput input;
    /**
     * length of decompressed file
     *
     */
    private long length;
    /**
     * current position in decompressed buffer
     *
     */
    private long bufferPos;
    /**
     * chunk position
     *
     */
    private int chunkPos;
    /**
     * inflated position of the decompressed buffer
     *
     */
    private int bufferOffset;
    /**
     * size of actual data in decompressed buffer. It can be less than chunk
     * size, because of flushes and seeks.
     */
    private int bufsize;
    /**
     * deflated position of buffer
     *
     */
    private long bufferInflatedPos;
    /**
     * inflater to decompress data
     *
     */
    private ReadDataTransformer inflater;
    /**
     * decompressed data buffer
     *
     */
    private SharedBufferCache.SharedBuffer buffer;
    /**
     * buffer to read compressed data chunks
     *
     */
    private byte[] readBuffer;
    /**
     * chunk directory: entries of inflated position of chunks
     *
     */
    private long[] inflatedPositions;
    /**
     * chunk directory: entries of position of compressed chunks
     *
     */
    private long[] chunkPositions;
    /**
     * chunk directory: length if inflated data of chunks
     *
     */
    private int[] inflatedLengths;
    /**
     * maximum size of inflated chunk, for seek optimizations
     *
     */
    private int maxInflatedLength;
    /**
     * actual end of real data position = position of chunk directory
     *
     */
    private long endOfFilePosition;
    /**
     * for chunk CRC calculation
     *
     */
    private CRC32 crc;
    /**
     * true if all chunks are in sequence and there is no need for chunk scan
     * and overwrite check
     */
    private SharedBufferCache memCache;
    private String name;
    private DecompressionChunkCache cache;
   private int maxChunkSize;
    private int maxReadSize;
    private final Object READ_BUFFER_LOCK = new Object();

    public TransformedIndexInput(String pName, IndexInput openInput, ReadDataTransformer inflater, DecompressionChunkCache cache, SharedBufferCache memCache) throws IOException {
        super(pName);
        this.input = openInput;
        this.crc = new CRC32();
        bufferOffset = 0;
        bufferPos = 0;
        chunkPos = 0;
        this.name = pName;
        bufsize = 0;
        this.cache = cache;
        bufferInflatedPos = -1;
        this.inflater = inflater;
        buffer = memCache.newBuffer(8192);
        readBuffer = new byte[8192];
        this.memCache = memCache;

        if (input.length() >= 16) {
            long magic = input.readLong();
            if (magic!=38483828l) {
                throw new IOException("Invalid magic number");
            }
            int configLen = input.readVInt();
            byte[] config = new byte[configLen];
            input.readBytes(config, 0, configLen);
            inflater.setConfig(config);

            readChunkDirectory();
            if (chunkPositions.length > 0) {
                input.seek(chunkPositions[0]);
            }
            bufferPos = 0;
            chunkPos = 0;
            bufferOffset = 0;
            bufsize = 0;
        } else {
            throw new IOException("Invalid chunked file");
        }

    
    }

    
    /**
     * read chunk directory
     *
     * @throws IOException
     */
    private void readChunkDirectory() throws IOException {
       input.seek(input.length() - 16);
       long magic =    input.readLong();
       length = input.readLong();
        if (magic < AbstractTransformedIndexOutput.MAGIC_NUMBER) {
            // if size has not been written (no magic), the directory does not exist and has
            // to be reconstructed by reading file
            scanPositions();
        } else {
            // read the directory
            // position if written at end of the file
            input.seek(input.length() - 8*3);
            endOfFilePosition = input.readLong();
            input.seek(endOfFilePosition);
            readDecompressImp(false);
            IndexInput in = new ByteIndexInput(name + ".tmp", buffer.data);
            // if chunk directory is large, buffers are too big, so reset them
            // release buffer and realocate it later
            buffer.data = null;
            readBuffer = new byte[512];
            int entries = in.readVInt();
            inflatedPositions = new long[entries];
            chunkPositions = new long[entries];
            inflatedLengths = new int[entries];
            long lastFilePos = 0;
            for (int i = 0; i < entries; i++) {
                inflatedPositions[i] = in.readVLong();
                chunkPositions[i] = in.readVLong();
                final int infLen = inflatedLengths[i] = in.readVInt();
                if (inflatedPositions[i] + inflatedLengths[i] > length || inflatedPositions[i] < 0 || inflatedLengths[i] < 0) {
                    // fallbakck to scan since directory seems to be corrupted
                    scanPositions();
                    return;
                }
                if (infLen > maxChunkSize) {
                    maxChunkSize = infLen;
                }
                final int readLen = (int) (chunkPositions[i] - lastFilePos);
                if (maxReadSize < readLen) {
                    maxReadSize = readLen;
                }
                lastFilePos = chunkPositions[i];
            }
            // realocate buffer at maximum chunk size
            buffer.data = new byte[maxChunkSize];
            readBuffer = new byte[maxReadSize + 4];
            in.close();
        }
//        System.out.println("Index length="+inflatedLengths.length);
    }

    /**
     * rebuilds directory by scanning the file. It tries to recover not properly
     * closed files without chunk directory
     *
     * @throws IOException
     */
    private void scanPositions() throws IOException {
        long fileLen = input.length();
        List<Long> chunks = new ArrayList<Long>();
        List<Long> inflated = new ArrayList<Long>();
        List<Integer> sizes = new ArrayList<Integer>();
        length = 0;
        while (input.getFilePointer() < fileLen) {
            long chunkPos = input.getFilePointer();
            long inflatedPos = input.readVLong();
            long crc = input.readVLong();
            int chunkSize = input.readVInt();
            int inflatedSize = input.readVInt();
            chunks.add(chunkPos);
            inflated.add(inflatedPos);
            sizes.add(inflatedSize);
            if (inflatedSize > maxChunkSize) {
                maxChunkSize = inflatedSize;
            }
            if (maxReadSize < inflatedSize) {
                maxReadSize = inflatedSize;
            }
            length += inflatedSize;
            input.seek(input.getFilePointer() + chunkSize);
        }
        inflatedLengths = new int[sizes.size()];
        inflatedPositions = new long[sizes.size()];
        chunkPositions = new long[sizes.size()];
        for (int i = 0; i < sizes.size(); i++) {
            inflatedLengths[i] = sizes.get(i);
            inflatedPositions[i] = inflated.get(i);
            chunkPositions[i] = chunks.get(i);
        }
        buffer.data = new byte[maxChunkSize];
        readBuffer = new byte[maxReadSize + 4];
        endOfFilePosition = input.length();
    }

    /**
     * find next proper chunk for sequential read. If seek operation followed by
     * write was made, next chunk is not logically next in the file. This
     * function finds next consequent chunk for bufferPos and if necessary
     * updates offsets and buffer position.
     *
     * This situation is not caught by check overwritten, since no overwriting
     * was made.
     *
     * Example chunk layout: ([pos=0,len=10], [pos = 4, len = 4],
     * [pos=10,len=15])
     *
     * When reading chunk 0, chunk 1 is merged into 0, but chunkPos is 1. On
     * next read chunk 1 must be skipped and seek to chunk 2 must be executed.
     *
     * @return offset inside decomprsesed buffer that has current bufferPos
     * @throws IOException
     */
    private int seekToChunk() throws IOException {
        if (inflatedPositions[chunkPos] == bufferPos) {
            return 0;
        }
        // for performance reason check, next chunk if it is on correct location
        if (chunkPos + 1 < inflatedPositions.length) {
            if (inflatedPositions[chunkPos + 1] == bufferPos) {
                return 0;
            }
        } else {
            // EOF
            throw new EOFException();
        }
        final int fchunk = findFirstChunk(bufferPos);
        // check for aligned reads (tyipical situation), especially for checkOverwritten
        for (int i = fchunk; i < inflatedPositions.length && inflatedPositions[i] <= bufferPos; i++) {
            if (inflatedPositions[i] == bufferPos) {
                if (input.getFilePointer() != chunkPositions[i]) {
                    //System.out.println("Correcting position for chunk "+i+"  loc="+inflatedPositions[i]+" oldLoc="+inflatedPositions[chunkPos]);
                    input.seek(chunkPositions[i]);
                }
                //System.out.println("Missed seek chunk "+chunkPos+" actual "+i+" opos="+inflatedPositions[chunkPos]+" npos="+inflatedPositions[i]);
                chunkPos = i;
                return 0;
            }
        }
        // in case seek write was on chunk boundary, realing the buffer and change offset
        // this is NOT generlaisation of preveus case
        // if it is called inside checkOvewritten, it is definetly inifinite loop
        // All situations like this should becaght and merged by check overwritten
        // But just for the case.
        System.out.println("Warning chunk " + chunkPos + "  at " + bufferPos + " not cought by overwriten. Using fallback");
        for (int i = fchunk; i < inflatedPositions.length; i++) {
            if (bufferPos >= inflatedPositions[i] && bufferPos < inflatedPositions[i] + inflatedLengths[i]) {
                int newOffset = (int) (bufferPos - inflatedPositions[i]);
                bufferPos = inflatedPositions[i];
                chunkPos = i;
                if (input.getFilePointer() != chunkPositions[i]) {
                    input.seek(chunkPositions[i]);
                }
                return newOffset;
            }
        }

        // seek hapened and was written beyond EOF. The hole has to be emulated
        // but might be an error
//        System.out.println("Hole at pos "+bufferPos);
//        return -1;
        throw new IOException("Chunk not found for " + name + " position " + bufferPos);
    }

    private void readDecompress() throws IOException {
        if (input.getFilePointer() >= endOfFilePosition) {
            throw new EOFException("Over EOF" + name + "  input=" + input.getFilePointer() + "  max=" + endOfFilePosition);
        }       
        readDecompressImp(true);       
    }

    private synchronized void readDecompressImp(final boolean hasDeflatedPosition) throws IOException {
        bufferPos += bufsize;
        if (hasDeflatedPosition && bufferPos >= length) {
            throw new EOFException("Beyond eof read " + name + " " + bufferPos + ">=" + length);
        }
        final int locBufferOffset;
        // since next chunk could be generated by seek back and write, find proper chunk from directory
        if (hasDeflatedPosition) {
            locBufferOffset = seekToChunk();
        } else {
            locBufferOffset = 0;
        }
        final long currentPos = input.getFilePointer();
        final long cachepos = bufferPos;
        byte[] cacheData = null;
        if (hasDeflatedPosition && cache != null) {
            cache.lock(cachepos);
            cacheData = cache.getChunk(cachepos);
        }
        try {
            if (cacheData != null) {
                bufsize = cacheData.length;
                if (buffer.refCount > 1) {
                    buffer.refCount--;
                    buffer = memCache.newBuffer(maxChunkSize);
                } else if (bufsize > buffer.data.length) {
                    buffer.data = new byte[maxChunkSize];
                }
                System.arraycopy(cacheData, 0, buffer.data, 0, bufsize);
                if (chunkPos < chunkPositions.length - 1) {
                    input.seek(chunkPositions[chunkPos + 1]);
                } else {
                    input.seek(endOfFilePosition);
                }
            } else {
                if (hasDeflatedPosition) {
                    final long inflatedPos = input.readVLong();
                    if (bufferPos != inflatedPos) {
                        throw new IOException("Invalid compression chunk location " + bufferPos + "!=" + inflatedPos);
                    }
                }
                final long chunkCRC = input.readVLong();
                final int compressed = input.readVInt();
                bufsize = input.readVInt();
                //  System.out.println("Decompressing " + input + " at " + input.getFilePointer()+" size="+bufsize);
                if (buffer.refCount > 1) {
                    buffer.refCount--;
                    buffer = memCache.newBuffer(maxChunkSize);
                }
                if (!hasDeflatedPosition && bufsize > buffer.data.length) {
                    buffer.data = new byte[bufsize];
                }
                //System.out.println("Reading "+name+" cp="+currentPos+" dp="+bufferPos+" len="+bufsize);
                // we are at current position ie. buffer allready contains data
                if (bufferInflatedPos == currentPos) {
                    input.seek(input.getFilePointer() + compressed);
                } else {
                    bufferInflatedPos = currentPos;
                    //           System.out.println("Decompress at " + currentPos + " " + cache);
                    int lcnt;
                    synchronized (READ_BUFFER_LOCK) {
                        if (compressed > readBuffer.length) {
                            readBuffer = new byte[compressed];
                        }
                        input.readBytes(readBuffer, 0, compressed);
                        lcnt = inflater.transform(readBuffer, 0, compressed, buffer.data, bufsize);
                        // did not transform
                        if (lcnt < 0) {
                            lcnt = compressed;
                            System.arraycopy(readBuffer, 0, buffer.data, 0, lcnt);
                        }
                    }
                    if (lcnt != bufsize) {
                        throw new IOException("Incorrect buffer size " + lcnt + "!=" + bufsize);
                    }
                    //calculate CRC for consistency
                    if (crc != null) {
                        crc.reset();
                        crc.update(buffer.data, 0, bufsize);
                        if (crc.getValue() != chunkCRC) {
                            throw new IOException("CRC mismatch");
                        }
                    }
                    if (hasDeflatedPosition && cache != null) {
                        cache.putChunk(cachepos, buffer.data, bufsize);
                    }
                }
            }
        } finally {
            if (hasDeflatedPosition && cache != null) {
                cache.unlock(cachepos);
            }
        }
        bufferOffset = locBufferOffset;
        bufferInflatedPos = currentPos;
        chunkPos++;
    }

    @Override
    public byte readByte() throws IOException {
      
        if (bufferOffset >= bufsize) {
            readDecompress();
        }       
        return buffer.data[bufferOffset++];
    }

    @Override
    public void readBytes(byte[] b, int boffset, int len) throws IOException {
        if (len < bufsize - bufferOffset) {
            System.arraycopy(buffer.data, bufferOffset, b, boffset, len);
            bufferOffset += len;
            return;
        }
        int llen = len;
        int loffset = boffset;
        while (llen > 0) {
            int toCopy = llen;
            if (toCopy > bufsize - bufferOffset) {
                toCopy = bufsize - bufferOffset;
            }
            System.arraycopy(buffer.data, bufferOffset, b, loffset, toCopy);

            loffset += toCopy;
            llen -= toCopy;
            bufferOffset += toCopy;

            if (bufferOffset >= bufsize && llen > 0 && input.getFilePointer() < endOfFilePosition) {
                readDecompress();
            }
        }
    }

    private Exception closedPath;
    
    @Override
    public void close() throws IOException {
        if (input != null) {
            input.close();
            memCache.release(buffer);
            input = null;
            try {
                throw new Exception();
            } catch (Exception ex) {
                closedPath = ex;
            }
        } else {
            throw new IOException("Already closed", closedPath);
        }
        
    }
    
    @Override
    public IndexInput clone() {
        TransformedIndexInput clone = (TransformedIndexInput) super.clone();
        clone.input = (IndexInput) input.clone();
        // increase reference count to buffer, so next time someone changes data, it is duplicated
        clone.buffer.refCount++;
        // readBuffer is shared with all clones
        clone.inflater = (ReadDataTransformer) inflater.copy();
        return clone;
    }

    @Override
    public long getFilePointer() {
        return bufferPos + bufferOffset-offset;
    }

    private int findFirstChunk(long pos) throws IOException {
        // find chunk index from indlated positions
        int i = 0;
        if (inflatedPositions.length < 100 && maxInflatedLength > 0) {
            while (i < inflatedPositions.length && !((inflatedPositions[i] <= pos) && (inflatedPositions[i] + inflatedLengths[i] > pos))) {
                i++;
            }
        } else {
            i = Arrays.binarySearch(inflatedPositions, pos - maxInflatedLength - 1) - 1;
            if (i < 0) {
                i = 0;
            }
            if (i >= inflatedLengths.length || !((inflatedPositions[i] <= pos) && (inflatedPositions[i] + inflatedLengths[i] > pos))) {
                i = 0;
            }
            while (i < inflatedPositions.length && !((inflatedPositions[i] <= pos) && (inflatedPositions[i] + inflatedLengths[i] > pos))) {
                i++;
            }
        }

        assert i >= 0 : "Invalid chunk offset table";
        if (i == inflatedLengths.length && pos >= length) {
            return inflatedLengths.length - 1;
        }
        // overshoot for one on purpose        
        if (i >= inflatedLengths.length) {
            throw new IOException("Incorrect chunk directory. Seek pos=" + pos + " last chunkPos=" + (inflatedPositions[inflatedLengths.length - 1] + inflatedLengths[inflatedLengths.length - 1]) + " length=" + length);
        }
        return i;
    }

    @Override
    public void seek(long pos) throws IOException {
        pos = pos +offset;
        // check if position is in current buffer
        //System.out.println(name+" Seek="+pos);
        if (pos >= bufferPos) {
            long ioffset = pos - bufferPos;
            if (ioffset < bufsize) {
                bufferOffset = (int) ioffset;
                return;
            }
        }
        int i = findFirstChunk(pos);
        long newBufferPos = inflatedPositions[i];
        if (newBufferPos != bufferPos || bufsize == 0) {
            bufferPos = newBufferPos;
            chunkPos = i;
            bufsize = 0;
            input.seek(chunkPositions[i]);
            readDecompress();
        }
        bufferOffset = (int) (pos - bufferPos);
        if (bufferOffset > bufsize) {
            throw new IOException("Incorrect chunk directory");
        }
        assert bufferOffset >= 0 && bufferOffset < bufsize && bufferOffset < length;
    }

    @Override
    public long length() {
        if (maxLen!=-1) {
            return maxLen;
        }
        return length;
    }


    @Override
    public IndexInput slice(String string, long pos, long length) throws IOException {
        TransformedIndexInput slice = (TransformedIndexInput) this.clone();
        slice.offset = pos;
        slice.maxLen = length;
        slice.seek(0);
        return slice;
    }
}