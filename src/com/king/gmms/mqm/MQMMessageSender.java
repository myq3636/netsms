package com.king.gmms.mqm;

import java.util.ArrayList;

import com.king.framework.SystemLogger;
import com.king.gmms.GmmsUtility;
import com.king.gmms.customerconnectionfactory.InternalMQMConnectionFactory;
import com.king.gmms.domain.A2PCustomerManager;
import com.king.gmms.domain.ModuleManager;
import com.king.gmms.messagequeue.OperatorMessageQueue;
import com.king.gmms.util.ExpiredMessageQueueWithSafeExit;
import com.king.message.gmms.GmmsMessage;
import com.king.message.gmms.GmmsStatus;
import com.king.message.gmms.MessageStoreManager;

/**
 * <p>
 * Title:
 * </p>
 * 
 * <p>
 * Description:
 * </p>
 * 
 * <p>
 * Copyright: Copyright (c) 2006
 * </p>
 * 
 * <p>
 * Company:
 * </p>
 * 
 * @author not attributable
 * @version 1.0
 */
public class MQMMessageSender implements Runnable {
	private static SystemLogger log = SystemLogger
			.getSystemLogger(MQMMessageSender.class);
	private ExpiredMessageQueueWithSafeExit messages;
	private GmmsUtility gmmsUtility = null;
	private MessageStoreManager msm = null;
	private InternalMQMConnectionFactory factory = null;	

	public MQMMessageSender(ExpiredMessageQueueWithSafeExit messages) {
		gmmsUtility = GmmsUtility.getInstance();
		msm = gmmsUtility.getMessageStoreManager();
		this.messages = messages;		
		factory = InternalMQMConnectionFactory.getInstance();
	}

	public void putMsg(GmmsMessage msg) {
		messages.put(msg);
	}

	public void putMsg(ArrayList<GmmsMessage> msgList) {
		messages.addAll(msgList);
	}

	public void run() {
		log.info("MQMMessageSender Thread start!");
		ModuleManager moduleManager = ModuleManager.getInstance();
		String queueName = gmmsUtility.getRouterModule();
		while (true) {
			try {
				//for Citic retry								
				try {
					int resendThrottle = gmmsUtility.getResendDRThrottle();
					if (resendThrottle !=0 && 1000/resendThrottle>0) {
						Thread.sleep(1000/resendThrottle);
					}					
				} catch (Exception e) {
					log.info("MQM Throttling sleep error.", e);
				}
				GmmsMessage msg = (GmmsMessage) messages.get(1000L);
				if (msg != null) {
					queueName = moduleManager.selectRouter(msg);
					OperatorMessageQueue queue = factory.getMessageQueue(msg, queueName);					
					if(queue == null){
			        	String aliveRouterQueue = moduleManager.selectAliveRouter(queueName,msg);
			        	queue = factory.getMessageQueue(msg, aliveRouterQueue);
			        	if(queue == null){
		            		ArrayList<String> failedRouters = new ArrayList<String>();
		            		failedRouters.add(queueName);
		            		failedRouters.add(aliveRouterQueue);
		            		aliveRouterQueue = moduleManager.selectAliveRouter(failedRouters, msg);
		            		while(aliveRouterQueue != null){
		            			queue = factory.getMessageQueue(msg, aliveRouterQueue);
		            			if(queue == null){
		            				failedRouters.add(aliveRouterQueue);
		            				aliveRouterQueue = moduleManager.selectAliveRouter(failedRouters, msg);
		            			}else{
		            				break;
		            			}
		            		}
		            	}
			        	queueName = aliveRouterQueue;
			    	}
					String deliveryChannel = msg.getDeliveryChannel();	
					if(deliveryChannel!=null){
						msg.setDeliveryChannel(deliveryChannel+":"+queueName);
					}else{
						msg.setDeliveryChannel(queueName);
					}
					if (!sendMessage(queue, msg)) {
						handleMessageError(msg);
					} else {
						if(log.isInfoEnabled()){
							log.info(msg,"one messages to be sent to Core Engine.");
						}
					}
				}
			} catch (Exception ex) {
				log.error("Error accessing messages from messagestore", ex);
			}
		}
	}

	/**
	 * 
	 * @param module
	 * @param message
	 */
	private boolean sendMessage(OperatorMessageQueue queue, GmmsMessage message) {	
		if (queue != null) {
			if (!queue.putMsg(message)) {
				return false;
			}
		} else {
			if(log.isInfoEnabled()){
				log.info(message, "Can not find the alive delivery router");
			}
			return false;
		}
		return true;
	}

	/**
	 * handleMessageError
	 * 
	 * @param msg
	 */
	private void handleMessageError(GmmsMessage msg) {
		if (GmmsMessage.MSG_TYPE_SUBMIT.equalsIgnoreCase(msg.getMessageType())
				|| GmmsMessage.MSG_TYPE_DELIVERY.equalsIgnoreCase(msg
						.getMessageType())) {
			msg.setStatus(GmmsStatus.SERVER_ERROR);
		} else if (GmmsMessage.MSG_TYPE_DELIVERY_REPORT_QUERY
				.equalsIgnoreCase(msg.getMessageType())) {
			msg.setStatus(GmmsStatus.FAIL_QUERY_DELIVERREPORT);
		} else if (GmmsMessage.MSG_TYPE_DELIVERY_REPORT.equalsIgnoreCase(msg
				.getMessageType())) {
			msg.setStatusCode(GmmsStatus.FAIL_SENDOUT_DELIVERYREPORT.getCode());
		}
		msm.handleMessageError(msg);
	}
}
