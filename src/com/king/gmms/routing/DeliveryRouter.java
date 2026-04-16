package com.king.gmms.routing;

import java.util.List;

import com.king.db.DatabaseStatus;
import com.king.framework.A2PService;
import com.king.framework.SystemLogger;
import com.king.gmms.GmmsUtility;
import com.king.gmms.connectionpool.systemmanagement.ConnectionManagementForCore;
import com.king.gmms.customerconnectionfactory.InternalCoreEngineConnectionFactory;
import com.king.gmms.domain.ModuleConnectionInfo;
import com.king.gmms.domain.ModuleManager;
import com.king.gmms.ha.systemmanagement.SystemListener;
import com.king.gmms.ha.systemmanagement.SystemSession;
import com.king.gmms.ha.systemmanagement.SystemSessionFactory;
import com.king.gmms.ha.systemmanagement.pdu.ModuleRegisterAck;
import com.king.gmms.ha.systemmanagement.pdu.SystemPdu;
import com.king.gmms.listener.InternalCoreEngineListener;
import com.king.gmms.processor.CsmProcessorHandler;
import com.king.gmms.processor.DBBackupHandler;
import com.king.gmms.processor.MessageProcessorHandler;
import com.king.gmms.processor.SenderRentHandler;
import com.king.gmms.processor.SystemStatusChecker;

public class DeliveryRouter implements A2PService{

	private static SystemLogger log = SystemLogger.getSystemLogger(DeliveryRouter.class);
	private InternalCoreEngineConnectionFactory factory = null;
	private InternalCoreEngineListener internalListener = null;
	private ModuleManager manager = null;
    protected SystemListener systemListener =  null;
    protected SystemSession systemSession =  null; // system client
	private SystemSessionFactory sysFactory = null;
    protected boolean isEnableSysMgt = false;
    protected boolean canHandover = false;
    private GmmsUtility gmmsUtility = null;
    private SystemStatusChecker systemStatusChecker = null;
    protected String module;

	public DeliveryRouter(){
		gmmsUtility = GmmsUtility.getInstance();
		isEnableSysMgt = gmmsUtility.isSystemManageEnable();
		canHandover = gmmsUtility.isDBHandover();
        module = System.getProperty("module");
        if(canHandover||isEnableSysMgt){//db ha enable || system management enable
	       systemListener = SystemListener.getInstance();
    	   try{
            	sysFactory = SystemSessionFactory.getInstance();
     			systemSession = sysFactory.getSystemSessionForFunction();
           }catch(Exception e){
        	   log.warn("SystemSessionFactory init failed:",e);
           }
        }
		internalListener = InternalCoreEngineListener.getInstance();
		manager = ModuleManager.getInstance();
	}
    /**
     * startService
     */
	public boolean startService() {
		if(canHandover||isEnableSysMgt){
			systemListener.start();
        }
		//ADSServerMonitor.getInstance().start();// start thread to monitor the DNS server connection
		DatabaseStatus dbstatus = DatabaseStatus.MASTER_USED;
		String redisStatus = "M";
		if(canHandover||isEnableSysMgt){
	        if(systemSession!=null){
	        	ModuleRegisterAck ack = sysFactory.moduleRegisterInDetail();
	        	if(ack!=null){
	        		String dbstatusStr = ack.getDbStatus();
	        		dbstatus = DatabaseStatus.get(dbstatusStr);
	        		redisStatus = ack.getRedisStatus();
	        	}
	        }else{
	        	log.info("SystemSession is null!!");
	        }
        }
		gmmsUtility.initRedisClient(redisStatus);
		gmmsUtility.initDBManager(dbstatus);
		gmmsUtility.initCDRManager();
		factory = InternalCoreEngineConnectionFactory.getInstance();
		List<ModuleConnectionInfo> moduleList = manager.getConnectionInfo4ServiceModule();
		ModuleConnectionInfo conn = null;
		if(moduleList != null && !moduleList.isEmpty()){
			for(int i = 0; i < moduleList.size() ; i++){
				conn = moduleList.get(i);
				if(conn != null){
					factory.initInternalConnectionFactory(conn.getConnectionName());
				}
			}
		}
		internalListener.start();
		DeliveryRouterHandler.getInstance();
		MessageProcessorHandler.getInstance();
		CsmProcessorHandler.getInstance();
		DBBackupHandler.getInstance();
		//SenderRentHandler.getInstance();
		
		if(gmmsUtility.isStoreDRModeEnable()){
			systemStatusChecker = new SystemStatusChecker();
			systemStatusChecker.start();
		}
		
		try {
			String moduleName = System.getProperty("module");
	        if (ModuleManager.getInstance().getRouterModules().contains(moduleName)) {
	        	gmmsUtility.getCustomerManager().loadRoutingInfoToRedis(moduleName);
	        	gmmsUtility.getCustomerManager().loadSenderBlacklistToRedis(moduleName);
	        	gmmsUtility.getCustomerManager().loadRecipientBlacklistToRedis(moduleName);
	        	gmmsUtility.getCustomerManager().loadSenderWhitelistToRedis(moduleName);
	        	gmmsUtility.getCustomerManager().loadContentActionlistToRedis(moduleName, true);
	        	gmmsUtility.getCustomerManager().loadContentActionlistToRedis(moduleName, false);
	        	gmmsUtility.getCustomerManager().loadSystemRoutingReplaceToRedis(moduleName);
	        }
		} catch (Exception e) {
			log.error("load routing to redis failed.",e);
		}
		return true;
	}
	/**
     * stopService
     */
	public boolean stopService() {
		if(canHandover||isEnableSysMgt){
			beforeStop();
	        systemListener.stop();
	        if(systemSession!=null){
	        	systemSession.shutdown();
	        }
        }
		internalListener.stop();
		if(systemStatusChecker != null){
			systemStatusChecker.stop();
		}
		return true;
	}
	/**
	 * send stop request
	 */
	public void beforeStop() {
		if(canHandover||isEnableSysMgt){
			ConnectionManagementForCore	systemManager = ConnectionManagementForCore.getInstance();
    		boolean flag = systemManager.moduleStop(module);
    		if(flag){
    			SystemPdu message = SystemPdu.createPdu(SystemPdu.COMMAND_MODULE_STOP_REQUEST);
    			SystemPdu response = systemSession.sendAndReceive(message);
    			if(log.isInfoEnabled()){
					log.info("Received MODULE_STOP_Response:{}",response);
    			}
    		}
    	}
	}
}
