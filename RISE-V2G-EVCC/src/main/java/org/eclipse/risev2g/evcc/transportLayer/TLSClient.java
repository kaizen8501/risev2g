/*******************************************************************************
 *  Copyright (c) 2015 Marc Mültin (Chargepartner GmbH).
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  Contributors:
 *    Dr.-Ing. Marc Mültin (Chargepartner GmbH) - initial API and implementation and initial documentation
 *******************************************************************************/
package org.eclipse.risev2g.evcc.transportLayer;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.eclipse.risev2g.shared.enumerations.GlobalValues;
import org.eclipse.risev2g.shared.misc.V2GTPMessage;
import org.eclipse.risev2g.shared.utils.SecurityUtils;

public class TLSClient extends StatefulTransportLayerClient {
	
	/*
	 *  Lazy instantiation of the Singleton since a TCP connection might not be
	 *  initialized if the SECCDiscovery message exchange failed.
	 *  The volatile keyword ensures that multiple threads handle the uniqueTCPClientInstance
	 *  variable correctly when it is being initialized to the TCPClient instance.
	 */
	private static volatile TLSClient uniqueTLSClientInstance;
	private SSLSocket tlsSocketToServer;
	
	public TLSClient() {} 
	
	/**
	 * Checks for an instance and creates one if there isn't one already.
	 * The synchronized block is only entered once as long as there is no existing instance of the
	 * TLSClient (safes valuable resource).
	 * @return
	 */
	public static TLSClient getInstance() {
		if (uniqueTLSClientInstance == null) {
			synchronized (TLSClient.class) {
				if (uniqueTLSClientInstance == null) {
					uniqueTLSClientInstance = new TLSClient();
				}
			}
		}
		
		return uniqueTLSClientInstance;
	}
	
	
	/**
	 * Initializes the TLS client as soon as a SECCDiscoveryRes message arrived.
	 * 
	 * @param host The address of the SECC's TLS server to connect to
	 * @param port The port of the SECC's TLS server to connect to
	 */
	public boolean initialize(Inet6Address host, int port) {
		super.initialize();
		
		try {
			/*
			 * Setting the system property for the keystore and truststore via 
			 * - System.setProperty("javax.net.ssl.keyStore", [filePath given as a String])
			 * - System.setProperty("javax.net.ssl.trustStore", [filePath given as a String])
			 * does not work in a JAR file since only getResourceAsStream works there (which on the other
			 * hand only returns an InputStream, not a file resource). Thus use setSSLFactories()
			 */
			SecurityUtils.setSSLContext(
					GlobalValues.EVCC_KEYSTORE_FILEPATH.toString(), 
					GlobalValues.EVCC_TRUSTSTORE_FILEPATH.toString(),
					GlobalValues.PASSPHRASE_FOR_CERTIFICATES_AND_KEYS.toString());
			
			SSLSocketFactory sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
			setTlsSocketToServer((SSLSocket) sslSocketFactory.createSocket(host, port));
			setInStream(getTlsSocketToServer().getInputStream());
			setOutStream(getTlsSocketToServer().getOutputStream());
			
			/*
			 * The EVCC shall support at least one cipher suite as listed below according to 
			 * the standard. An implementer may decide to choose only one of them:
			 * - TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256
			 * - TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256
			 */
			String[] enabledCipherSuites = {"TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256", "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256"};
			getTlsSocketToServer().setEnabledCipherSuites(enabledCipherSuites);
			
			// Set the supported TLS protocol
			String[] enabledProtocols = {"TLSv1.2"};
			getTlsSocketToServer().setEnabledProtocols(enabledProtocols);

			getLogger().debug("TLS client connection established \n\t from link-local address " +
							  getClientAddress() + " and port " + getClientPort() + 
							  "\n\t to host " + host.getHostAddress() + " and port " + port);
			
			return true;
		} catch (UnknownHostException e) {
			getLogger().error("TCP client connection failed (UnknownHostException)!", e);
		} catch (IOException e) {
			getLogger().error("TCP client connection failed (IOException)!", e);
		} catch (NullPointerException e) {
			getLogger().fatal("NullPointerException while trying to set keystores, resource path to keystore/truststore might be incorrect");
			return false;
		}
		
		return false;
	}
	
	
	@Override
	public void run() {
		while (!Thread.interrupted()) { 
			if (getTimeout() > 0) {
				try {
					getTlsSocketToServer().setSoTimeout(getTimeout());
					
					if (!processIncomingMessage()) break;
					
				} catch (SocketTimeoutException e) {
					stopAndNotify("A timeout occurred while waiting for response message", null);
					break;
				} catch (IOException e2) {
					stopAndNotify("An IOException occurred while trying to read message", e2);
					break;
				}
			}
		}
	}
	
	
	@Override
	public void send(V2GTPMessage message, int timeout) {
		setV2gTPMessage(null);
		
		try {
			getOutStream().write(message.getMessage());
			getOutStream().flush();
			getLogger().debug("Message sent");
			setTimeout(timeout);
		} catch (IOException e) {
			getLogger().error("An undefined IOException occurred while trying to send message", e);
		}
	}
	
	@Override
	public void stop() {
		if (!isStopAlreadyInitiated()) {
			getLogger().debug("Stopping TLS client ...");
			setStopAlreadyInitiated(true);
			
			try {
				getInStream().close();
				getOutStream().close();
				getTlsSocketToServer().close();
				Thread.currentThread().interrupt();
			} catch (IOException e) {
				getLogger().error("Error occurred while trying to close TCP socket to server", e);
			}
			
			getLogger().debug("TLS client stopped");
		}
	}
	
	
	public SSLSocket getTlsSocketToServer() {
		return tlsSocketToServer;
	}

	public void setTlsSocketToServer(SSLSocket tlsSocketToServer) {
		this.tlsSocketToServer = tlsSocketToServer;
	}

}