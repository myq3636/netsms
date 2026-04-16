/**
 * Standard Logged
 */

package com.king.gmms.listener;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import com.king.db.DatabaseStatus;
import com.king.framework.A2PService;
import com.king.framework.A2PThreadGroup;
import com.king.framework.SystemLogger;
import com.king.gmms.GmmsUtility;
import com.king.gmms.connectionpool.systemmanagement.ConnectionManagementForFunction;
import com.king.gmms.ha.systemmanagement.SystemListener;
import com.king.gmms.ha.systemmanagement.SystemSession;
import com.king.gmms.ha.systemmanagement.SystemSessionFactory;
import com.king.gmms.ha.systemmanagement.pdu.ModuleRegisterAck;
import com.king.gmms.throttle.ReportInMsgCountTimer;
import com.king.gmms.throttle.ResetDynamicCustInThresholdTimer;

public abstract class AbstractServer implements A2PService, Runnable {
	private static SystemLogger log = SystemLogger
			.getSystemLogger(AbstractServer.class);
	protected GmmsUtility gmmsUtility;
	protected volatile boolean running;
	protected String module;
	protected ServerSocket server = null;
	protected InternalAgentListener agentListener = null;
	protected SystemListener systemListener = null;
	protected ServerSocket serverSocket;// Init in run
	protected Thread serverThread;
	protected int port; // smpp server listener port
	protected SystemSession systemSession = null; // system client
	protected SystemSessionFactory sysFactory = null;
	protected boolean isEnableSysMgt = false;
    protected boolean canHandover = false;
	protected ReportInMsgCountTimer reportInMsgCountTimer = null;
	protected ResetDynamicCustInThresholdTimer resetDynamicCustInThresholdTimer = null;


	public AbstractServer() {
		this.gmmsUtility = GmmsUtility.getInstance();
		running = true;
		agentListener = InternalAgentListener.getInstance();
		module = System.getProperty("module");
		port = Integer.parseInt(gmmsUtility.getModuleProperty("Port").trim());
		isEnableSysMgt = gmmsUtility.isSystemManageEnable();
		canHandover = gmmsUtility.isDBHandover();
		if (canHandover || isEnableSysMgt) {
			systemListener = SystemListener.getInstance();
			try {
				sysFactory = SystemSessionFactory.getInstance();
				systemSession = sysFactory.getSystemSessionForFunction();
				reportInMsgCountTimer = new ReportInMsgCountTimer(systemSession, 
						gmmsUtility.getReportModuleIncomingMsgCountInterval());
				resetDynamicCustInThresholdTimer = 
					new ResetDynamicCustInThresholdTimer(gmmsUtility.getDynamicCustInThresholdExipreTime()/3);
			} catch (Exception e) {
				log.warn(e, e);
			}
		}
	}

	/**
	 * start the thread
	 */
	public void run() {
		try {
			if (serverSocket == null) {
				// int port =
				// Integer.parseInt(gmmsUtility.getModuleProperty("Port").trim());
				if (port <= 0) {
					throw new IOException("Port number is:" + port);
				}
				serverSocket = new ServerSocket(port);
				serverSocket.setReuseAddress(true);
				String info = module + " began to listen on port:" + port;
				log.info(info);
				serverSocket.setSoTimeout(10 * 1000);
			}
			while (isRunning()) {
				Socket nextClient = null;
				try {
					nextClient = serverSocket.accept();
					String host = nextClient.getInetAddress().getHostAddress();
					if (nextClient != null) {
						if (gmmsUtility.isAddressScreened(host)) {
							nextClient.close();
							continue;
						}
						log.info("{} accept a connection on port {} from host {}",
										module, host, port);
						createSession(nextClient);
					}
				} catch (IOException e) {
					// NO log needed here because the
					// java.net.SocketTimeoutException is regularly thrown
				}
			}
		} catch (IOException e) {
			log.error(e, e);
		} finally {
			running = false;
			try {
				if (serverSocket != null) {
					serverSocket.close();
					serverSocket = null;
				}
			} catch (IOException e1) {
				log.warn(e1, e1);
			}
		}
	}

	public boolean startService() {
		try {
			serverThread = new Thread(A2PThreadGroup.getInstance(), this,
					module);
			serverThread.start();
			String redisStatus = "M";
			if (canHandover || isEnableSysMgt) {
				systemListener.start();
				if (systemSession != null) {
					ModuleRegisterAck ack = systemSession.moduleRegisterInDetail();
		        	if(ack!=null){
		        		redisStatus = ack.getRedisStatus();
		        	}
					
					reportInMsgCountTimer.startTimer("reportInMsgCountTimer");
					resetDynamicCustInThresholdTimer.startTimer("resetDynamicCustInThresholdTimer");
				}
				
			}
			gmmsUtility.initRedisClient(redisStatus);
			log.info("{} starting...", module);
			return true;
		} catch (Exception ex) {
			log.fatal("serverThread initialize fail!", ex);
			System.exit(-1);
			return false;
		}
	}

	public boolean stopService() {
		running = false;
		try {
			if (canHandover || isEnableSysMgt) {
				beforeStop();
				systemListener.stop();
				if (systemSession != null) {
					systemSession.shutdown();
				}
			}
			serverThread.join();
			if (serverSocket != null) {
				serverSocket.close();
				serverSocket = null;
			}
		} catch (Exception e) {
			log.warn(e, e);
		}
			log.info("{} stopped!", module);
		return true;
	}

	protected abstract void createSession(Socket clientSocket)
			throws IOException;

	public boolean isRunning() {
		return running;
	}

	/**
	 * send stop request
	 */
	public void beforeStop() {
		if (canHandover || isEnableSysMgt) {
			reportInMsgCountTimer.stopTimer();
			resetDynamicCustInThresholdTimer.stopTimer();
			ConnectionManagementForFunction systemManager = ConnectionManagementForFunction
					.getInstance();
			boolean flag = systemManager.moduleStop(module);
			if (flag) {
				systemSession.moduleStop();
			}
		}
	}
}
