package org.vydrya.jdbc.pool.version1;

/**
 * Implementation ConnectionPoolRuntimeExceptions. <br>
 * ConnectionPoolRuntimeExceptions extends {@link RuntimeException}.
 * 
 * @author vydrya_vitaliy.
 * @version 1.0.
 */
public class ConnectionPoolRuntimeExceptions extends RuntimeException {
	private static final long serialVersionUID = -729381865620359768L;

	/**
	 * Default constructor.
	 */
	public ConnectionPoolRuntimeExceptions() {
		super();
	}

	/**
	 * Constructor with parameters.
	 * 
	 * @param message
	 *            - message exception.
	 */
	public ConnectionPoolRuntimeExceptions(final String message) {
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
	public ConnectionPoolRuntimeExceptions(final String message, final Throwable cause) {
		super(message, cause);
	}

	/**
	 * Constructor with parameters.
	 * 
	 * @param cause
	 *            - cause exceptions.
	 */
	public ConnectionPoolRuntimeExceptions(final Throwable cause) {
		super(cause);
	}
}
