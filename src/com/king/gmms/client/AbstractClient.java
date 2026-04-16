package com.king.gmms.client;

import java.net.ServerSocket;

import com.king.db.DatabaseStatus;
import com.king.framework.A2PService;
import com.king.framework.SystemLogger;
import com.king.gmms.GmmsUtility;
import com.king.gmms.domain.A2PCustomerManager;
import com.king.gmms.ha.systemmanagement.SystemListener;
import com.king.gmms.ha.systemmanagement.SystemSession;
import com.king.gmms.ha.systemmanagement.SystemSessionFactory;
import com.king.gmms.ha.systemmanagement.pdu.ModuleRegisterAck;
import com.king.gmms.listener.InternalAgentListener;

public abstract class AbstractClient implements A2PService {
	private static SystemLogger log = SystemLogger.getSystemLogger(AbstractClient.class);
	protected GmmsUtility gmmsUtility;
    protected volatile boolean running;
    protected String module;
    protected ServerSocket server = null;
    protected InternalAgentListener agentListener =  null;//internal listener
    protected A2PCustomerManager ctm = null;
    protected SystemListener systemListener =  null; // system listener
    protected SystemSession systemSession =  null; // system client
    protected boolean isEnableSysMgt = false;
    protected boolean canHandover = false;
    
    public AbstractClient() {
    	 gmmsUtility = GmmsUtility.getInstance();
         running = true;
         agentListener = InternalAgentListener.getInstance();
         ctm = gmmsUtility.getCustomerManager();
    }
    /**
     * only used by long connection
     */
    protected boolean initSystemManagement(){
    	isEnableSysMgt = gmmsUtility.isSystemManageEnable();
    	canHandover = gmmsUtility.isDBHandover();
        if(canHandover || isEnableSysMgt){
       	 	systemListener = SystemListener.getInstance();
       	 	systemListener.start();
            try{
            	String redisStatus = "M";
            	SystemSessionFactory sysFactory = SystemSessionFactory.getInstance();
           	 	systemSession = sysFactory.getSystemSessionForFunction();
           	    ModuleRegisterAck ack = systemSession.moduleRegisterInDetail();
	        	if(ack!=null){	        		
	        		redisStatus = ack.getRedisStatus();
	        	}
	        	gmmsUtility.initRedisClient(redisStatus);
	        	if(ack!=null){
	    			return ack.getResponseCode() == 0;
	    		}else{
	    			return false;
	    		}
            }catch(Exception e){
            	log.warn(e, e);
            	return false;
            }
        }
    	return true;
    }
    public boolean isRunning() {
        return running;
    }
}
