package com.king.gmms.throttle;

import com.king.framework.SystemLogger;

/**
 * <p>Title: ThrottlingTimemark</p>
 * <p>Description: store the timemark of throttling</p>
 * <p>Copyright: Copyright (c) 2001-2010</p>
 * <p>Company: King</p>
 *
 * @version 6.1
 * @author: Neal
 */
public class ThrottlingTimemark {
    private static SystemLogger log = SystemLogger.getSystemLogger(ThrottlingTimemark.class);
    private int num;
    private int point;
    private long[] dateArray;
    private Object mutex = new Object();
    
    /**
     * check time window, 1s
     */
    public final static long Period = (long)1 * 1000;

    /**
     * Constructor
     *
     * @param arrayLen int
     */
    public ThrottlingTimemark(int arrayLen) {
        num = arrayLen;
        dateArray = new long[num];
        point = 0;
    }

    /**
     * judge the throttling number by judging the time
     *
     * @param ssid int
     * @return pass: 0, 
     *         </p>failed: sliding window start time
     */
    public long processThrottlingControl(boolean isIncoming) {
    	long slidingWinStartTime = 0;
        boolean pass = false;
        long current;
        
        synchronized(mutex) {
            current = System.currentTimeMillis();
            slidingWinStartTime = dateArray[point];
            if (current - dateArray[point] > Period) {
                dateArray[point] = current;
                if (point < dateArray.length - 1) {
                    point++;
                }
                else {
                    point = 0;
                }
                pass = true;
                // Wake up any threads waiting for a throttle slot to open
                mutex.notifyAll();
            }
        }
        
        if (!pass) {
        	if (isIncoming) {
        		if(log.isTraceEnabled()){
                    log.trace("Refused by Incoming Throttling Control. Current: {}, Before: {}" ,current, slidingWinStartTime);
        		}
            } else {
        		if(log.isTraceEnabled()){
                	log.trace("Refused by Outgoing Throttling Control. Current: {}, Before: {}",current, slidingWinStartTime);
        		}
            }
        	return slidingWinStartTime;
        }

        return 0;
    }

    /**
     * Returns the precise number of milliseconds the caller should wait
     * until the oldest throttling slot will expire and a new send is permitted.
     * This allows callers to replace Thread.sleep(200) with a precise wait.
     * If the result is <= 0, the window has already expired and a retry should succeed.
     */
    public long getWaitMillis() {
        synchronized(mutex) {
            long oldest = dateArray[point];
            long remaining = Period - (System.currentTimeMillis() - oldest);
            return remaining > 0 ? remaining : 1;
        }
    }

    /**
     * Block the current thread precisely until the throttle window opens.
     * On return, the caller should immediately retry isAllowedToSend().
     */
    public void awaitSlot() throws InterruptedException {
        synchronized(mutex) {
            long waitMs = getWaitMillis();
            if (waitMs > 0) {
                mutex.wait(waitMs);
            }
        }
    }

    public int getDateArraySize() {
    	return num;
    }

}
