package me.errolalpay.giphyproxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

/**
 * A special case of PeerToPeerServer, the ExternalServicePeerToPeerServer uses outbound connections to external
 * services as peers. When asked to locate a peer for a given connection, the ExternalServicePeerToPeerServer class
 * connects to the specified external service, and returns a connection to it.
 * 
 * The content of the TCP byte streams are not consumed in any way, therefore is it the client connections'
 * responsibility to know the service they are consuming, and to handle all params, auth, SSL, keep-alives etc.
 * 
 * @author Errol Alpay
 */

public class ExternalServicePeerToPeerServer extends PeerToPeerServer {

	private InetSocketAddress m_outboundSocketAddress = null;

	/**
	 * 
	 * @param inboundPortNumber
	 *            The local port to listen on
	 * @param outboundServerName
	 *            The external service server name
	 * @param outboundPortNumber
	 *            The external service port
	 * 
	 * @throws IOException
	 */
	public ExternalServicePeerToPeerServer( int inboundPortNumber, String outboundServerName, int outboundPortNumber ) throws IOException {
		super( inboundPortNumber );

		// create the inet address for outbound connections. doing this once to improve performance by eliminating the
		// DNS lookup at runtime.
		m_outboundSocketAddress = new InetSocketAddress( outboundServerName, outboundPortNumber );

		// TODO: caching the IP address of the outbound service assumes its IP address will not change. add a separate
		// thread to periodically refresh this address.
	}
	@Override
	protected SocketChannel locatePeer( SocketChannel channel ) throws IOException {

		// connect to the outbound service
		System.err.println( "Connecting to outbound service" );
		SocketChannel outboundChannel = SocketChannel.open();
		outboundChannel.connect( m_outboundSocketAddress );
		while ( outboundChannel.finishConnect() == false ) {
		}

		return outboundChannel;
	}
}
