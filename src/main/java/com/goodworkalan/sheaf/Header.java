package com.goodworkalan.sheaf;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;

public class Header<K> extends BasicWritable
{
    private final Map<K, List<Integer>> offsets;

    public Header(long position, Map<K, List<Integer>> offsets, ByteBuffer byteBuffer, Lock lock)
    {
        super(position, byteBuffer, lock, new DirtyByteMap(byteBuffer.capacity()));
        this.offsets = offsets;
    }
    
    public Region get(K key)
    {
        List<Integer> offset = offsets.get(key);
        Dirtyable subDirtyable = new SubDirtyable(cleanable, offset.get(0), offset.get(1));
        getLock().lock();
        try
        {
            ByteBuffer byteBuffer = getByteBuffer();
            
            int position = byteBuffer.position();
            int limit = byteBuffer.limit();
            
            byteBuffer.position(offset.get(0));
            byteBuffer.limit(offset.get(0) + offset.get(1));
            
            ByteBuffer subByteBuffer = byteBuffer.slice();
            
            byteBuffer.position(position);
            byteBuffer.limit(limit);
            
            return new BasicRegion(position + offset.get(0), subByteBuffer, getLock(), subDirtyable);
        }
        finally
        {
            getLock().unlock();
        }
    }
}
