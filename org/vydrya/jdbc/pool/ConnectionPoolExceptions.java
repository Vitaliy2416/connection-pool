package org.vydrya.jdbc.pool;

import java.sql.SQLException;

/**
 * Implementation ConnectionPoolExceptions. <br>
 * ConnectionPoolExceptions extends {@link SQLException}.
 * 
 * @author vydrya_vitaliy.
 * @version 1.0.
 */
public class ConnectionPoolExceptions extends Exception {
	private static final long serialVersionUID = -5622424564322966596L;

	/**
	 * Default constructor.
	 */
	public ConnectionPoolExceptions() {
		super();
	}

	/**
	 * Constructor with parameters.
	 * 
	 * @param message
	 *            - message exception.
	 */
	public ConnectionPoolExceptions(final String message) {
		super(message);
	}

	/**
	 * Constructor with parameters.
	 * 
	 * @param message
	 *            - message exception.
	 * @param cause
	 *            - cause exceptions.
	 */
	public ConnectionPoolExceptions(final String message, final Throwable cause) {
		super(message, cause);
	}

	/**
	 * Constructor with parameters.
	 * 
	 * @param cause
	 *            - cause exceptions.
	 */
	public ConnectionPoolExceptions(final Throwable cause) {
		super(cause);
	}
}
