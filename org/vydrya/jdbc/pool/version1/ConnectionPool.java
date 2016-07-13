package org.vydrya.jdbc.pool.version1;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.LinkedList;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Implementation ConnectionPool.
 * 
 * @author vydrya_vitaliy.
 * @version 1.0.
 */
public class ConnectionPool {
	/**
	 * Lock for thread helper - waiting overflow pool.
	 */
	private final Object lock = new Object();
	/**
	 * Flag cleaning for the demon cleaning thread.
	 */
	private volatile boolean flagCleaning = true;
	/**
	 * Pool closed flag.
	 */
	private volatile boolean closed = false;
	/**
	 * {@link CleaningWorker}.
	 */
	private CleaningWorker cleaningWorker;

	/**
	 * Driver name.
	 */
	private final String driverName;
	/**
	 * Vendor URL.
	 */
	private final String url;
	/**
	 * The user name to use for the connection.
	 */
	private final String user;
	/**
	 * The password for the connection.
	 */
	private final String password;
	/**
	 * Min size connection.
	 */
	private final int minSize;
	/**
	 * Min size connection.
	 */
	private final int maxSize;
	/**
	 * Waiting time before removal connection of the pool connection.
	 */
	private final int timeOut;
	/**
	 * Contains all the free connections.
	 */
	private LinkedList<PooledConnection> freeConnections;
	/**
	 * Contains all the used connections.
	 */
	private LinkedList<PooledConnection> usedConnections;
	/**
	 * {@link DestroyProcessThread}.
	 */
	private DestroyProcessThread destroyProcessThread;

	/**
	 * Constructor with parameter.
	 * 
	 * @param driverName
	 *            - driver name.
	 * @param url
	 *            - vendor url.
	 * @param user
	 *            - the user name to use for the connection.
	 * @param password
	 *            - the password for the connection.
	 * @param minSize
	 *            - min size connection.
	 * @param maxSize
	 *            - max size connection.
	 * @param timeOut
	 *            - waiting time before removal connection of the pool connection.
	 * @throws ConnectionPoolExceptions
	 *             - if problems pool initialization.
	 */
	public ConnectionPool(final String driverName, final String url, final String user, final String password,
			final int minSize, final int maxSize, final int timeOut) throws ConnectionPoolExceptions {

		if ((minSize > maxSize) || (minSize < 1 || maxSize < 1)) {
			throw new IllegalArgumentException("Invalid pool size parameters");
		}

		this.driverName = driverName;
		this.url = url;
		this.user = user;
		this.password = password;
		this.minSize = minSize;
		this.maxSize = maxSize;
		this.timeOut = timeOut;
		initDriver();
		initHook();
		initConnectionPool();
		usedConnections = new LinkedList<>();
	}

	/**
	 * Returns the connection, if there is no free compounds {@link ConnectionPool#freeConnections} then it creates
	 * a new connected at a certain time {@link ConnectionPool#timeOut} millisecond.<br>
	 * At the expiration of the {@link ConnectionPool#timeOut} the connection is closed.
	 * 
	 * @return the {@link Connection}.
	 * @throws ConnectionPoolExceptions
	 *             - if problem get {@link Connection}.
	 */
	public synchronized Connection getConnection() throws ConnectionPoolExceptions {
		return borrowConnection();
	}

	/**
	 * Create new {@link Connection} or get of {@link ConnectionPool#freeConnections}.
	 * 
	 * @return the {@link Connection}.
	 * @throws ConnectionPoolExceptions
	 *             if problem creation or receipt {@link Connection}.
	 */
	protected PooledConnection borrowConnection() throws ConnectionPoolExceptions {
		if (this.closed) {
			throw new ConnectionPoolExceptions("Connection pool closed.");
		}
		PooledConnection con;
		try {
			if (!freeConnections.isEmpty()) {
				con = freeConnections.removeLast();
				con.resetClosed();
			} else {
				con = newConnection();
			}
		} catch (SQLException e) {
			throw new ConnectionPoolExceptions("Exceptions borrowConnection", e);
		}
		usedConnections.add(con);
		return con;
	}

	/**
	 * Close {@link ConnectionPool}.
	 */
	public synchronized void closeConnectionPool() {
		if (closed) {
			return;
		}
		if (cleaningWorker != null) {
			cleaningWorker.interrupt();
		}
		if (freeConnections != null && !freeConnections.isEmpty()) {
			listClear(freeConnections);
		}
		if (usedConnections != null && !usedConnections.isEmpty()) {
			listClear(usedConnections);
		}
		closed = true;
	}

	/**
	 * Returns true if {@link #close close} has been called, and the connection pool is unusable.
	 * 
	 * @return the {@link ConnectionPool#closed}.
	 */
	public boolean isClosed() {
		return this.closed;
	}

	/**
	 * Getter {@link ConnectionPool#minSize}.
	 * 
	 * @return the minSize.
	 */
	public int getMinSize() {
		return minSize;
	}

	/**
	 * Getter {@link ConnectionPool#maxSize}.
	 * 
	 * @return the maxSize.
	 */
	public int getMaxSize() {
		return maxSize;
	}

	/**
	 * Getter {@link ConnectionPool#timeOut}.
	 * 
	 * @return the timeOut.
	 */
	public int getTimeOut() {
		return timeOut;
	}

	/**
	 * Returns the total size of this pool, this includes both busy and idle connections.
	 * 
	 * @return int - number of established connections to the database.
	 */
	public synchronized int getSize() {
		return usedConnections.size() + freeConnections.size();
	}

	/**
	 * Returns the number of connections that are in use.
	 * 
	 * @return int - number of established connections that are being used by the application.
	 */
	public synchronized int getUse() {
		return usedConnections.size();
	}

	/**
	 * Returns the number of idle connections.
	 * 
	 * @return int - number of established connections not being used.
	 */
	public synchronized int getFree() {
		return freeConnections.size();
	}

	/**
	 * Returns an instance of {@link Connection} to the connection pool.
	 * 
	 * @param con
	 *            - {@link PooledConnection}.
	 */
	protected void returnConnection(final PooledConnection con) {
		freeConnections.add(con);
		usedConnections.remove(con);
	}

	/**
	 * Run thread cleaning connection.
	 */
	protected void runCleaning() {
		if (freeConnections.size() > maxSize) {
			if (cleaningWorker == null) {
				cleaningWorker = new CleaningWorker();
			} else {
				synchronized (lock) {
					flagCleaning = true;
					lock.notifyAll();
				}
			}
		}
	}

	/**
	 * Create new connection.
	 * 
	 * @return the {@link PooledConnection}.
	 * @throws SQLException
	 *             if unable to establish a connection.
	 */
	protected PooledConnection newConnection() throws SQLException {
		Connection con = DriverManager.getConnection(url, this.user, this.password);
		return new PooledConnection(con);
	}

	/**
	 * Close last connection.
	 * 
	 * @param listConnections
	 *            - list connection.
	 */
	protected void close(final LinkedList<PooledConnection> listConnections) {
		try {
			listConnections.removeLast().connection.close();
		} catch (SQLException e) {
			System.err.println("Exception close connections: " + e.getMessage());
		}
	}

	/**
	 * Clear all connection.
	 * 
	 * @param list
	 *            - list connection.
	 */
	protected void listClear(final LinkedList<PooledConnection> list) {
		for (int i = 0; i < list.size(); i++) {
			close(list);
		}
		list.clear();
	}

	/**
	 * Getter {@link ConnectionPool#freeConnections}.
	 * 
	 * @return the freeConnections.
	 */
	protected LinkedList<PooledConnection> getFreeConnections() {
		return freeConnections;
	}

	/**
	 * Getter {@link ConnectionPool#usedConnections}.
	 * 
	 * @return the usedConnections.
	 */
	protected LinkedList<PooledConnection> getUsedConnections() {
		return usedConnections;
	}

	/**
	 * Setter {@link ConnectionPool#closed}.
	 * 
	 * @param closed
	 *            the closed to set.
	 */
	protected void setClosed(final boolean closed) {
		this.closed = closed;
	}

	/**
	 * Initial shutdown shutdown hook.
	 */
	protected void initHook() {
		destroyProcessThread = new DestroyProcessThread();
		Runtime.getRuntime().addShutdownHook(destroyProcessThread);
	}

	/**
	 * Getter {@link ConnectionPool#flagCleaning}.
	 * 
	 * @return the flagCleaning.
	 */
	protected boolean isFlagCleaning() {
		return flagCleaning;
	}

	/**
	 * Setter {@link ConnectionPool#flagCleaning}.
	 * 
	 * @param flagCleaning
	 *            the flagCleaning to set.
	 */
	protected void setFlagCleaning(final boolean flagCleaning) {
		this.flagCleaning = flagCleaning;
	}

	/**
	 * Getter {@link ConnectionPool#cleaningWorker}.
	 * 
	 * @return the cleaningWorker.
	 */
	protected CleaningWorker getCleaningWorker() {
		return cleaningWorker;
	}

	/**
	 * Setter {@link ConnectionPool#cleaningWorker}.
	 * 
	 * @param cleaningWorker
	 *            the cleaningWorker to set.
	 */
	protected void setCleaningWorker(final CleaningWorker cleaningWorker) {
		this.cleaningWorker = cleaningWorker;
	}

	/**
	 * Getter {@link ConnectionPool#destroyProcessThread}.
	 * 
	 * @return the destroyProcessThread.
	 */
	protected DestroyProcessThread getDestroyProcessThread() {
		return destroyProcessThread;
	}

	/**
	 * Setter {@link ConnectionPool#destroyProcessThread}.
	 * 
	 * @param destroyProcessThread
	 *            the destroyProcessThread to set.
	 */
	protected void setDestroyProcessThread(final DestroyProcessThread destroyProcessThread) {
		this.destroyProcessThread = destroyProcessThread;
	}

	/**
	 * Getter {@link ConnectionPool#lock}.
	 * 
	 * @return the lock.
	 */
	protected Object getLock() {
		return lock;
	}

	/**
	 * Getter {@link ConnectionPool#driverName}.
	 * 
	 * @return the driverName.
	 */
	protected String getDriverName() {
		return driverName;
	}

	/**
	 * Load driver and register driver.
	 * 
	 * @throws ConnectionPoolExceptions
	 *             - if not loaded or not registered drives.
	 */
	private void initDriver() throws ConnectionPoolExceptions {
		try {
			Class.forName(driverName);
		} catch (ClassNotFoundException e) {
			throw new ConnectionPoolRuntimeExceptions("exception init driver", e);
		}
	}

	/**
	 * First initial {@link ConnectionPool}.
	 * 
	 * @throws ConnectionPoolExceptions
	 *             if problem initial connection.
	 */
	private void initConnectionPool() throws ConnectionPoolExceptions {
		freeConnections = new LinkedList<>();
		for (int i = 0; i < minSize; i++) {
			try {
				PooledConnection pooledConnection = newConnection();
				freeConnections.add(pooledConnection);
			} catch (SQLException e) {
				throw new ConnectionPoolExceptions("Exception initConnection", e);
			}
		}
	}

	/**
	 * Implementation CleaningWorker. <br>
	 * CleaningWorker extends {@link Thread}.
	 * 
	 * @author vydrya_vitaliy.
	 * @version 1.0.
	 */
	protected class CleaningWorker extends Thread {
		/**
		 * Default constructor.
		 */
		public CleaningWorker() {
			this.setDaemon(true);
			this.start();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.lang.Thread#run()
		 */
		@Override
		public void run() {
			while (!isInterrupted()) {
				try {
					while (!flagCleaning) {
						synchronized (lock) {
							lock.wait();
						}
					}
					while (true) {
						TimeUnit.MILLISECONDS.sleep(timeOut);
						synchronized (ConnectionPool.this) {
							if ((freeConnections.size() - maxSize) > 0) {
								close(freeConnections);
							} else {
								break;
							}
						}
					}
				} catch (InterruptedException e) {
					interrupt();
				}
				flagCleaning = false;
			}
		}
	}

	/**
	 * Implementation PooledConnection. <br>
	 * PooledConnection implements {@link Connection}.
	 * 
	 * @author vydrya_vitaliy.
	 * @version 1.0.
	 */
	protected class PooledConnection implements Connection {
		/**
		 * Flag closed connect.
		 */
		private boolean closedConnect;
		/**
		 * {@link Connection}.
		 */
		private Connection connection;

		/**
		 * Constructor with parameters.
		 * 
		 * @param c
		 *            - {@link Connection}.
		 */
		public PooledConnection(final Connection c) {
			this.connection = c;
			closedConnect = false;
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#clearWarnings()
		 */
		@Override
		public void clearWarnings() throws SQLException {
			checkClosed();
			connection.clearWarnings();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#close()
		 */
		@Override
		public void close() throws SQLException {
			synchronized (ConnectionPool.this) {
				if (this.closedConnect) {
					return;
				}
				returnConnection(this);
				runCleaning();
				this.closedConnect = true;
			}
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#commit()
		 */
		@Override
		public void commit() throws SQLException {
			checkClosed();
			connection.commit();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#createArrayOf(java.lang.String, java.lang.Object[])
		 */
		@Override
		public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
			checkClosed();
			return connection.createArrayOf(typeName, elements);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#createBlob()
		 */
		@Override
		public Blob createBlob() throws SQLException {
			checkClosed();
			return connection.createBlob();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#createClob()
		 */
		@Override
		public Clob createClob() throws SQLException {
			checkClosed();
			return connection.createClob();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#createNClob()
		 */
		@Override
		public NClob createNClob() throws SQLException {
			checkClosed();
			return connection.createNClob();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#createSQLXML()
		 */
		@Override
		public SQLXML createSQLXML() throws SQLException {
			checkClosed();
			return connection.createSQLXML();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#createStatement()
		 */
		@Override
		public Statement createStatement() throws SQLException {
			checkClosed();
			return connection.createStatement();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#createStatement(int, int)
		 */
		@Override
		public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
			checkClosed();
			return connection.createStatement(resultSetType, resultSetConcurrency);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#createStatement(int, int, int)
		 */
		@Override
		public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
				throws SQLException {
			checkClosed();
			return connection.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#createStruct(java.lang.String, java.lang.Object[])
		 */
		@Override
		public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
			checkClosed();
			return connection.createStruct(typeName, attributes);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#getAutoCommit()
		 */
		@Override
		public boolean getAutoCommit() throws SQLException {
			checkClosed();
			return connection.getAutoCommit();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#getCatalog()
		 */
		@Override
		public String getCatalog() throws SQLException {
			checkClosed();
			return connection.getCatalog();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#getClientInfo()
		 */
		@Override
		public Properties getClientInfo() throws SQLException {
			return connection.getClientInfo();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#getClientInfo(java.lang.String)
		 */
		@Override
		public String getClientInfo(String name) throws SQLException {
			return connection.getClientInfo(name);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#getHoldability()
		 */
		@Override
		public int getHoldability() throws SQLException {
			checkClosed();
			return connection.getHoldability();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#getMetaData()
		 */
		@Override
		public DatabaseMetaData getMetaData() throws SQLException {
			checkClosed();
			return connection.getMetaData();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#getTransactionIsolation()
		 */
		@Override
		public int getTransactionIsolation() throws SQLException {
			checkClosed();
			return connection.getTransactionIsolation();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#getTypeMap()
		 */
		@Override
		public Map<String, Class<?>> getTypeMap() throws SQLException {
			checkClosed();
			return connection.getTypeMap();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#getWarnings()
		 */
		@Override
		public SQLWarning getWarnings() throws SQLException {
			checkClosed();
			return connection.getWarnings();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#isClosed()
		 */
		@Override
		public boolean isClosed() throws SQLException {
			return isClosedConnect();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#isReadOnly()
		 */
		@Override
		public boolean isReadOnly() throws SQLException {
			checkClosed();
			return connection.isReadOnly();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#isValid(int)
		 */
		@Override
		public boolean isValid(int timeout) throws SQLException {
			checkClosed();
			return connection.isValid(timeout);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#nativeSQL(java.lang.String)
		 */
		@Override
		public String nativeSQL(String sql) throws SQLException {
			checkClosed();
			return connection.nativeSQL(sql);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#prepareCall(java.lang.String)
		 */
		@Override
		public CallableStatement prepareCall(String sql) throws SQLException {
			checkClosed();
			return connection.prepareCall(sql);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#prepareCall(java.lang.String, int, int)
		 */
		@Override
		public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
				throws SQLException {
			checkClosed();
			return connection.prepareCall(sql, resultSetType, resultSetConcurrency);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#prepareCall(java.lang.String, int, int, int)
		 */
		@Override
		public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
				int resultSetHoldability) throws SQLException {
			checkClosed();
			return connection.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#prepareStatement(java.lang.String)
		 */
		@Override
		public PreparedStatement prepareStatement(String sql) throws SQLException {
			checkClosed();
			return connection.prepareStatement(sql);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#prepareStatement(java.lang.String, int)
		 */
		@Override
		public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
			checkClosed();
			return connection.prepareStatement(sql, autoGeneratedKeys);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#prepareStatement(java.lang.String, int[])
		 */
		@Override
		public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
			checkClosed();
			return connection.prepareStatement(sql, columnIndexes);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#prepareStatement(java.lang.String, java.lang.String[])
		 */
		@Override
		public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
			checkClosed();
			return connection.prepareStatement(sql, columnNames);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#prepareStatement(java.lang.String, int, int)
		 */
		@Override
		public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
				throws SQLException {
			checkClosed();
			return connection.prepareStatement(sql, resultSetType, resultSetConcurrency);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#prepareStatement(java.lang.String, int, int, int)
		 */
		@Override
		public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
				int resultSetHoldability) throws SQLException {
			checkClosed();
			return connection.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#releaseSavepoint(java.sql.Savepoint)
		 */
		@Override
		public void releaseSavepoint(Savepoint savepoint) throws SQLException {
			checkClosed();
			connection.releaseSavepoint(savepoint);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#rollback()
		 */
		@Override
		public void rollback() throws SQLException {
			checkClosed();
			connection.rollback();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#rollback(java.sql.Savepoint)
		 */
		@Override
		public void rollback(Savepoint savepoint) throws SQLException {
			checkClosed();
			connection.rollback(savepoint);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#setAutoCommit(boolean)
		 */
		@Override
		public void setAutoCommit(boolean autoCommit) throws SQLException {
			checkClosed();
			connection.setAutoCommit(autoCommit);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#setCatalog(java.lang.String)
		 */
		@Override
		public void setCatalog(String catalog) throws SQLException {
			checkClosed();
			connection.setCatalog(catalog);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#setClientInfo(java.util.Properties)
		 */
		@Override
		public void setClientInfo(Properties properties) throws SQLClientInfoException {
			connection.setClientInfo(properties);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#setClientInfo(java.lang.String, java.lang.String)
		 */
		@Override
		public void setClientInfo(String name, String value) throws SQLClientInfoException {
			connection.setClientInfo(name, value);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#setHoldability(int)
		 */
		@Override
		public void setHoldability(int holdability) throws SQLException {
			checkClosed();
			connection.setHoldability(holdability);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#setReadOnly(boolean)
		 */
		@Override
		public void setReadOnly(boolean readOnly) throws SQLException {
			checkClosed();
			connection.setReadOnly(readOnly);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#setSavepoint()
		 */
		@Override
		public Savepoint setSavepoint() throws SQLException {
			checkClosed();
			return connection.setSavepoint();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#setSavepoint(java.lang.String)
		 */
		@Override
		public Savepoint setSavepoint(String name) throws SQLException {
			checkClosed();
			return connection.setSavepoint(name);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#setTransactionIsolation(int)
		 */
		@Override
		public void setTransactionIsolation(int level) throws SQLException {
			checkClosed();
			connection.setTransactionIsolation(level);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#setTypeMap(java.util.Map)
		 */
		@Override
		public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
			checkClosed();
			connection.setTypeMap(map);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Wrapper#isWrapperFor(java.lang.Class)
		 */
		@Override
		public boolean isWrapperFor(Class<?> iface) throws SQLException {
			checkClosed();
			return connection.isWrapperFor(iface);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Wrapper#unwrap(java.lang.Class)
		 */
		@Override
		public <T> T unwrap(Class<T> iface) throws SQLException {
			checkClosed();
			return connection.unwrap(iface);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#setSchema(java.lang.String)
		 */
		@Override
		public void setSchema(String schema) throws SQLException {
			connection.setSchema(schema);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#getSchema()
		 */
		@Override
		public String getSchema() throws SQLException {
			return connection.getSchema();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#abort(java.util.concurrent.Executor)
		 */
		@Override
		public void abort(Executor executor) throws SQLException {
			connection.abort(executor);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#setNetworkTimeout(java.util.concurrent.Executor, int)
		 */
		@Override
		public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
			connection.setNetworkTimeout(executor, milliseconds);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.sql.Connection#getNetworkTimeout()
		 */
		@Override
		public int getNetworkTimeout() throws SQLException {
			return connection.getNetworkTimeout();
		}

		/**
		 * Check closing {@link PooledConnection#connection}.
		 * 
		 * @throws SQLException
		 *             - if the connection is closed.
		 */
		protected void checkClosed() throws SQLException {
			if (isClosedConnect()) {
				throw new SQLNonTransientConnectionException("No operations allowed after connection closed.");
			}
		}

		/**
		 * Reset flag closed.
		 */
		protected void resetClosed() {
			closedConnect = false;
		}

		/**
		 * Getter {@link PooledConnection#closedConnect}.
		 * 
		 * @return the closedConnect.
		 */
		protected final boolean isClosedConnect() {
			return closedConnect;
		}

		/**
		 * Setter {@link PooledConnection#closedConnect}.
		 * 
		 * @param closedConnect
		 *            the closedConnect to set.
		 */
		protected void setClosedConnect(final boolean closedConnect) {
			this.closedConnect = closedConnect;
		}

		/**
		 * Getter {@link PooledConnection}.
		 * 
		 * @return the connection.
		 */
		protected Connection getConnection() {
			return connection;
		}
	}

	/**
	 * Implementation DestroyProcessThread. <br>
	 * DestroyProcessThread extends {@link Thread}.<br>
	 * For cleaning all resources before removing.
	 * 
	 * @author vydrya_vitaliy.
	 * @version 1.0.
	 */
	protected class DestroyProcessThread extends Thread {
		/*
		 * (non-Javadoc)
		 * 
		 * @see java.lang.Thread#run()
		 */
		@Override
		public void run() {
			closeConnectionPool();
		}
	}
}