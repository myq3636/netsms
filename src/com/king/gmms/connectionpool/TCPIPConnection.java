package com.king.gmms.connectionpool;

/**
 * <p>Title: </p>
 *
 * <p>Description: </p>
 *
 * <p>Copyright: Copyright (c) 2006</p>
 *
 * <p>Company: </p>
 *
 * @author not attributable
 * @version 1.0
 */

import com.king.framework.A2PThreadGroup;
import com.king.framework.SystemLogger;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * All time variable in this class is milliseconds
 */
public class TCPIPConnection {
	private static SystemLogger log = SystemLogger
			.getSystemLogger(TCPIPConnection.class);
	protected Socket socket;
	private TCPReceiver tcpReceiver;
	protected Receiver receiver = null;
	protected OutputStream os;
	protected InputStream is;
	protected int soTimeout;
	protected int readBufferSize;
	protected long eventInterval;
	protected long sendingInterval;
	protected long maxSilentTime;
	protected volatile boolean closed;
	protected volatile boolean async;
	protected final Object mutex;// Used for lock during sendAndReceive duration
	protected String connectionname;

	public TCPIPConnection(Socket socket) {
		if (socket == null) {
			throw new NullPointerException("Socket can't be null!");
		}
		closed = true;
		async = true;
		mutex = new Object();
		this.socket = socket;
		setSoTimeout(1, SECONDS);
		setEventInterval(20, MILLISECONDS);
		setReadBufferSize(10240);
		setSendingInterval(1, MILLISECONDS);
		setMaxSilentTime(2 * 60, SECONDS);
	}

	public void open() throws IOException {
		if (socket.isClosed() || !socket.isConnected()) {
			throw new IOException("Socket is not connected.");
		}
		closed = false;
		socket.setTcpNoDelay(true);
		os = new BufferedOutputStream(socket.getOutputStream());
		is = new BufferedInputStream(socket.getInputStream(),readBufferSize);
		tcpReceiver = new TCPReceiver("TCPReceiver_" + connectionname);
		tcpReceiver.start();
		if(log.isDebugEnabled()){
			log.debug("A receiver thread is started to receive data from socket.");
		}
	}

	public void setReceiver(Receiver receiver) {
		if (receiver != null) {
			this.receiver = receiver;
		}
	}

	public void close() {
		if (!closed) {
			synchronized (mutex) {
				if (closed)
					return;

				closed = true;

				try {
					if (is != null) {
						is.close();
						is = null;
					}
					if (os != null) {
						os.close();
						os = null;
					}
					if (socket != null && !socket.isClosed()) {
						socket.close();
						socket = null;
					}
				} catch (IOException e) {
					log.warn(e, e);
				}
				log.debug("TcpIpConnection closed.");
			}
		}
	}

	/**
	 * Wait until sending buffer is not full to send the data.
	 * 
	 * @param data
	 *            byte[]
	 * @throws IOException
	 */
	public void send(byte[] data) throws IOException {
		if (closed) {
			throw new IOException("Connection is not open!!");
		}
		if (data == null) {
			return;
		}
		synchronized (mutex) {
			if(os != null){
				os.write(data);
				os.flush();
			}else{
				throw new IOException("Connection is closed!!");
			}
		}
	}

	/**
	 * null is returned if socket timeout
	 * 
	 * @param data
	 *            byte[]
	 * @return java.nio.ByteBuffer
	 * @throws IOException
	 */
	public ByteBuffer sendAndReceive(byte[] data) throws IOException {
		if (closed) {
			throw new IOException("Connection is not open!!");
		}
		if (data == null) {
			return null;
		}
		ByteBuffer result = null;
		synchronized (mutex) {
			async = false;
			os.write(data);
			os.flush();

			try {
				mutex.wait(soTimeout);
			} catch (InterruptedException e) {
				Thread.interrupted();
				log.warn("TcpIpConnection is interrupted!", e);
			}

			result = tcpReceiver.getSyncResponse();
			tcpReceiver.clearSyncResponse();
			async = true;
		}
		return result;
	}

	public int getSoTimeout(TimeUnit timeUnit) {
		return (int) timeUnit.convert(soTimeout, MILLISECONDS);
	}

	public void setSoTimeout(int soTimeout, TimeUnit timeUnit) {
		if (!closed) {
			throw new IllegalStateException("The connection is already open!");
		}
		if (soTimeout < 0) {
			this.soTimeout = 50;
			log.error("SoTimeOut >= 0, but now it's: {}", soTimeout);
			return;
		}
		this.soTimeout = (int) MILLISECONDS.convert(soTimeout, timeUnit);
	}

	public boolean isClosed() {
		return closed;
	}

	public int getReadBufferSize() {
		return readBufferSize;
	}

	public void setReadBufferSize(int readBufferSize) {
		if (!closed) {
			throw new IllegalStateException("The connection is already open!");
		}
		if (readBufferSize <= 0) {
			throw new IllegalArgumentException(
					"ReadBufferSize > 0, but now it's:" + readBufferSize);
		}
		try {
			this.readBufferSize = readBufferSize;
			//socket.setReceiveBufferSize(readBufferSize / 2);
		} catch (Exception e) {
		}
	}

	public void setEventInterval(long eventInterval, TimeUnit timeUnit) {
		if (eventInterval < 0) {
			throw new IllegalArgumentException(
					"EventInterval >=0, but now it's:" + eventInterval);
		}
		this.eventInterval = MILLISECONDS.convert(eventInterval, timeUnit);
	}

	public long getEventInterval(TimeUnit timeUnit) {
		return timeUnit.convert(eventInterval, MILLISECONDS);
	}

	public long getSendingInterval(TimeUnit timeUnit) {
		return timeUnit.convert(sendingInterval, MILLISECONDS);
	}

	public void setSendingInterval(long sendingInterval, TimeUnit timeUnit) {
		if (sendingInterval < 0) {
			throw new IllegalArgumentException(
					"SendingInterval >=0, but now it's:" + sendingInterval);
		}
		this.sendingInterval = MILLISECONDS.convert(sendingInterval, timeUnit);
	}

	public long getMaxSilentTime(TimeUnit timeUnit) {
		return timeUnit.convert(maxSilentTime, MILLISECONDS);
	}

	/**
	 * Set to 0 means allowing unlimited.
	 * 
	 * @param maxSilentTime
	 *            long
	 * @param timeUnit
	 *            TimeUnit
	 */
	public void setMaxSilentTime(long maxSilentTime, TimeUnit timeUnit) {
		if (maxSilentTime < 0) {
			throw new IllegalArgumentException(
					"MaxSilentTime >=0, but now it's:" + maxSilentTime);
		}
		this.maxSilentTime = MILLISECONDS.convert(maxSilentTime, timeUnit);
	}

	protected void fireConnectionUnavailable() {
		close();
		if (receiver != null) {
			receiver.connectionUnavailable();
		}
	}

	public void setConnectionName(String connectionName) {
		this.connectionname = connectionName;
	}

	public String getConnectionName() {
		return this.connectionname;
	}

	private class TCPReceiver extends Thread {

		private ByteBuffer syncResponse;
		private String receiverName;

		public TCPReceiver(String receiverName) throws IOException {
			super(A2PThreadGroup.getInstance(), receiverName);
			this.receiverName = receiverName;
		}

		public void run() {
			try {
				ByteBuffer received = null;
				byte[] input = new byte[readBufferSize];;
				int len = -1;
				while (!closed) {
					len = -1;					
					len = is.read(input);

					if (closed) {
						break;
					}
					if (len > 0) {
						received = ByteBuffer.allocate(len).put(input, 0, len);
						received.rewind();
						fireReceived(received);
					} else if (len < 0) {
						log
								.debug("Connection unavailable due to closed by remote.");
						fireConnectionUnavailable();
						break;
					}
				}
			} catch (InterruptedIOException e) {
				fireConnectionUnavailable();
				log.warn(e, e);
			} catch (IOException e) {
				log.warn("Connection unavailable due to exception.", e);
				fireConnectionUnavailable();
			} finally {
				synchronized(mutex){
					try {
						if(is != null){
							is.close();
							is = null;
						}
						if (os != null) {
							os.close();
							os = null;
						}
						if (socket != null && !socket.isClosed()) {
							socket.close();
							socket = null;
						}
					} catch (IOException e) {
						log.warn(e, e);
					}
				}
				if(log.isInfoEnabled()){
					log.info("The receiver({}) is stoped",receiverName);
				}
			}
		}

		private void fireReceived(ByteBuffer received) {
			if (async) {
				if (receiver != null) {
					receiver.parse(received);
				}
			} else {
				this.syncResponse = received;
				synchronized (mutex) {
					mutex.notifyAll();
				}
			}
		}

		ByteBuffer getSyncResponse() {
			return syncResponse;
		}

		void clearSyncResponse() {
			syncResponse = null;
		}
	}

}
