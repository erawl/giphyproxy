package me.errolalpay.giphyproxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

public class ExternalServicePeerToPeerServer extends PeerToPeerServer {

	private InetSocketAddress m_outboundSocketAddress = null;

	public ExternalServicePeerToPeerServer( int inboundPortNumber, String outboundServerName, int outboundPortNumber ) throws IOException {
		super( inboundPortNumber );

		// create the inet address for outbound connections. doing this once to improve performance by eliminating the
		// DNS lookup at runtime.
		m_outboundSocketAddress = new InetSocketAddress( outboundServerName, outboundPortNumber );
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
