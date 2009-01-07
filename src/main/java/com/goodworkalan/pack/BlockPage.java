package com.goodworkalan.pack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Checksum;


/**
 * An application of a raw page that manages the page a list of data blocks.
 * <h4>Duplicate Soft References</h4>
 * <p>
 * The data page is the only page that can take advantage of the duplication
 * soft references in the raw page class. The raw page holds a soft
 * refernece to the byte buffer. It is itself soft referneced by the map of
 * pages by position in the pager.
 * <p>
 * All pages write their changes out to the byte buffer. We hold onto dirty
 * raw pages in a dirty page map. Once the raw page is written to disk we
 * let go of the hard reference to the raw page in the raw page map. It can
 * be collected.
 * <p>
 * The data page also contains a lock that keeps another mutator from
 * writing to it when it is being vacuumed. The lock is based on the
 * <code>wait</code> and <code>notify</code> methods of the data page
 * object. The byte buffer may be flused to disk, but a data page waiting
 * to be vacuumed still needs to be held in memory because of the lock.
 * <h4>Two-Stage Vacuum</h4>
 * <p>
 * Vacuum must work in two stages. The page is mirrored. The blocks that
 * are preceded by one or more freed blocks are copied into interim pages.
 * Then during journal play back, the compacting is performed by copying
 * the mirrored blocks into place over the freed blocks.
 * <p>
 * Once a page is mirrored, no other mutator can write to that page, since
 * that would put it out of sync with the mirroring of the page. If we
 * were to mirror a page and then another mutator updated a block in the
 * page, if the blocks is preceded by one or more freed blocks, then that
 * block would be reverted when we compact the page from the mirror.
 * <p>
 * Initially, you thought that a strategy was have the writing mutator
 * also update the mirror. This caused a lot of confusion, since now the
 * journal was changing after the switch to play back. How does one
 * mutator write to another mutator's journal? Which mutator commits that
 * change? This raised so many questions, I can't remember them all.
 * <p>
 * The mirrored property is checked before an mutator writes or frees a
 * block. If it is true, indicating that the page is mirrored but not
 * compacted, then the operation will block until the compacting makes the
 * vacuum complete.
 * <p>
 * Vacuums occur before all other play back operations. During play back
 * after a hard shutdown, we run the vacuums before all other operations.
 * We run the vacuums of each journal, then we run the remainder of each
 * journal.
 * <h4>Deadlock</h4>
 * <p>
 * Every once and a while, you forget and worry about deadlock. You're
 * afraid that one thread holding on a mirrored data page will attempt to
 * write to a mirrored data page of anther thread while that thread is
 * trying to write a mirrored data page held the this thread. This cannot
 * happen, of course, because vacuums happen before write or free
 * operations.
 * <p>
 * You cannot deadlock by mirroring, because only one mutator at a time
 * will ever vacuum a data page, because only one mutator at a time can
 * use a data page for block allocation.
 */
abstract class BlockPage
extends RelocatablePage
{
    protected int count;

    protected int remaining;

    public BlockPage()
    {
    }
    
    protected abstract int getDiskCount();
    
    protected abstract int getDiskCount(int count);

    public void create(RawPage rawPage, DirtyPageSet dirtyPages)
    {
        super.create(rawPage, dirtyPages);
        
        this.count = 0;
        this.remaining = rawPage.getPager().getPageSize() - Pack.BLOCK_PAGE_HEADER_SIZE;
        
        ByteBuffer bytes = rawPage.getByteBuffer();

        bytes.clear();

        rawPage.invalidate(0, Pack.BLOCK_PAGE_HEADER_SIZE);
        bytes.putLong(0L);
        bytes.putInt(getDiskCount());
        
        dirtyPages.add(rawPage);
    }

    private int getConsumed()
    {
        int consumed = Pack.BLOCK_PAGE_HEADER_SIZE;
        ByteBuffer bytes = getBlockRange();
        for (int i = 0; i < count; i++)
        {
            int size = getBlockSize(bytes);
            if (size > 0)
            {
                consumed += size;
            }
            advance(bytes, size);
        }
        return consumed;
    }

    public void load(RawPage rawPage)
    {    
        super.load(rawPage);

        ByteBuffer bytes = rawPage.getByteBuffer();

        bytes.clear();
        bytes.getLong();

        this.count = getDiskCount(bytes.getInt());
        this.remaining = getRawPage().getPager().getPageSize() - getConsumed();
    }
    
    public int getCount()
    {
        synchronized (getRawPage())
        {
            return count;
        }
    }

    public int getRemaining()
    {
        synchronized (getRawPage())
        {
            return remaining;
        }
    }

    @Override
    public void checksum(Checksum checksum)
    {
        ByteBuffer bytes = getRawPage().getByteBuffer();
        bytes.putLong(0, getChecksum(checksum));
        getRawPage().invalidate(0, Pack.CHECKSUM_SIZE);
    }

    protected int getBlockSize(ByteBuffer bytes)
    {
        int blockSize = bytes.getInt(bytes.position());
        assert blockSize != 0;
        assert Math.abs(blockSize) <= bytes.remaining();
        return blockSize;
    }

    protected long getAddress(ByteBuffer bytes)
    {
        return bytes.getLong(bytes.position() + Pack.COUNT_SIZE);
    }
    
    protected void advance(ByteBuffer bytes, int blockSize)
    {
        bytes.position(bytes.position() + Math.abs(blockSize));
    }

    /**
     * Return the byte buffer associated with this data page with the
     * position and limit set to the range of bytes that contain blocks.
     *
     * @return The byte buffer limited to the block range.
     */
    private ByteBuffer getBlockRange(ByteBuffer bytes)
    {
        bytes.position(Pack.BLOCK_PAGE_HEADER_SIZE);
        return bytes;
    }

    protected ByteBuffer getBlockRange()
    {
        return getBlockRange(getRawPage().getByteBuffer());
    }

    private boolean unmoved()
    {
        return getRawPage().getPage() == this;
    }

    /**
     * Advance to the block associated with the address in this page. If
     * found the position of the byte buffer will be at the start of the
     * full block including the block header. If not found the block is
     * after the last valid block.
     * 
     * @param bytes
     *            The byte buffer of this block page.
     * @param address
     *            The address to seek.
     * @return True if the address is found, false if not found.
     */
    protected boolean seek(ByteBuffer bytes, long address)
    {
        bytes = getBlockRange(bytes);
        int block = 0;
        while (block < count)
        {
            int size = getBlockSize(bytes);
            if (size > 0)
            {
                block++;
            }
            if (getAddress(bytes) == address)
            {
                return true;
            }
            advance(bytes, size);
        }
        return false;
    }
    
    public boolean contains(long address)
    {
        return unmoved() && seek(getRawPage().getByteBuffer(), address);
    }
    
    public int getBlockSize(long address)
    {
        synchronized (getRawPage())
        {
            ByteBuffer bytes = getRawPage().getByteBuffer();
            if (seek(bytes, address))
            {
                return getBlockSize(bytes);
            }
        }
        throw new IllegalArgumentException();
    }

    public List<Long> getAddresses()
    {
        List<Long> listOfAddresses = new ArrayList<Long>(getCount());
        synchronized (getRawPage())
        {
            ByteBuffer bytes = getBlockRange();
            int block = 0;
            while (block < getCount())
            {
                int size = getBlockSize(bytes);
                if (size > 0)
                {
                    block++;
                    listOfAddresses.add(getAddress(bytes));
                }
                advance(bytes, size);
            }
        }
        return listOfAddresses;
    }

    public boolean write(long address, ByteBuffer data, DirtyPageSet dirtyPages)
    {
        synchronized (getRawPage())
        {
            ByteBuffer bytes = getRawPage().getByteBuffer();
            if (seek(bytes, address))
            {
                int offset = bytes.position();
                int size = bytes.getInt();
                if (bytes.getLong() != address)
                {
                    throw new Danger(Pack.ERROR_BLOCK_PAGE_CORRUPT);
                }
                bytes.limit(offset + size);
                getRawPage().invalidate(bytes.position(), bytes.remaining());
                bytes.put(data);
                bytes.limit(bytes.capacity());
                dirtyPages.add(getRawPage());
                return true;
            }
            return false;
        }
    }

    public ByteBuffer read(long address, ByteBuffer dst)
    {
        synchronized (getRawPage())
        {
            ByteBuffer bytes = getRawPage().getByteBuffer();
            if (seek(bytes, address))
            {
                if (dst == null)
                {
                    dst = ByteBuffer.allocateDirect(getBlockSize(address) - Pack.BLOCK_HEADER_SIZE);
                }
                int offset = bytes.position();
                int size = bytes.getInt();
                if (bytes.getLong() != address)
                {
                    throw new IllegalStateException();
                }
                bytes.limit(offset + size);
                dst.put(bytes);
                bytes.limit(bytes.capacity());
                return dst;
            }
        }
        return null;
    }
    
    // Note that this must be called in a synchronized block.
    public long getChecksum(Checksum checksum)
    {
        checksum.reset();

        ByteBuffer bytes = getRawPage().getByteBuffer();
        bytes.clear();
        bytes.position(Pack.CHECKSUM_SIZE);
        
        for (int i = 0; i < Pack.COUNT_SIZE; i++)
        {
            checksum.update(bytes.get());
        }
        
        int block = 0;
        while (block < count)
        {
            int size = getBlockSize(bytes);
            if (size > 0)
            {
                for (int i = 0; i < size; i++)
                {
                    checksum.update(bytes.get());
                }
                block++;
            }
            else
            {
                bytes.position(bytes.position() + -size);
            }
        }
        
        return checksum.getValue();
    }

    public boolean verifyChecksum(RawPage rawPage, Recovery recovery)
    {
        Checksum checksum = recovery.getChecksum();
        checksum.reset();
        
        ByteBuffer bytes = rawPage.getByteBuffer();
        bytes.position(Pack.CHECKSUM_SIZE);
        
        for (int i = 0; i < Integer.SIZE / Byte.SIZE; i++)
        {
            checksum.update(bytes.get());
        }
        
        int count = bytes.getInt(Pack.CHECKSUM_SIZE);
        if ((count & Pack.COUNT_MASK) != 0)
        {
            count = (count & ~Pack.COUNT_MASK);
        }
        
        int block = 0;
        while (block < count)
        {
            int size = bytes.getInt(bytes.position());
            
            if (Math.abs(size) > bytes.remaining())
            {
                recovery.corruptDataPage(rawPage.getPosition());
                return false;
            }
            
            if (size > 0)
            {
                block++;
                for (int i = 0; i < size; i++)
                {
                    checksum.update(bytes.get());
                }
            }
            else
            {
                advance(bytes, size);
            }
        }
        
        long expected = checksum.getValue();
        long actual = bytes.getLong(0);
        
        if (expected != actual)
        {
            recovery.badUserChecksum(rawPage.getPosition());
            return false;
        }
        
        return true;
    }
    
    public boolean verifyAddresses(Recovery recovery)
    {
        boolean copacetic = true;
        Pager pager = getRawPage().getPager();
        ByteBuffer bytes = getBlockRange();
        int block = 0;
        while (block < count)
        {
            int size = getBlockSize(bytes);
            if (size > 0)
            {
                block++;
                long address = getAddress(bytes);
                if (address < pager.getFirstAddressPageStart() + Pack.ADDRESS_PAGE_HEADER_SIZE
                    || address >= pager.getUserBoundary().getPosition())
                {
                    recovery.badUserAddress(getRawPage().getPosition(), address);
                    copacetic = false;
                }
                AddressPage addresses = pager.getPage(address, new AddressPage());
                if (getRawPage().getPosition() != addresses.dereference(address))
                {
                    recovery.badUserAddress(getRawPage().getPosition(), address);
                    copacetic = false;
                }
            }
            else
            {
                advance(bytes, size);
            }
        }
        return copacetic;
    }
}