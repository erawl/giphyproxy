package me.errolalpay.app;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.Buffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Proxies inbound TCP requests to an another TCP service.
 * 
 * Principal of operation: This proxy server listens for inbound connections like typical TCP servers do. Once an
 * inbound connection is accepted, the proxy then opens a companion connection, outbound to the outbound service. The 2
 * connections are then indexed together, forwards and backwards, in a HashMap such that the companion connection can be
 * found with predictable performance. Whenever data is read from one connection, the companion connection is retrieved,
 * and that same data is then written to the companion. This "read from connection, write to companion" works in both
 * directions, regardless of which connection (inbound or outbound) sent the data. This read/write back and forth TCP
 * conversation continues until either connection closes itself. At that time, the proxy server assumes the conversation
 * is over, and destroys both connections as well as their membership in the index.
 * 
 * This proxy has an emphasis on privacy, therefore it doesnt log ip addresses, number of bytes written, date/time, etc.
 * It limits logging to only that which helps us see the service is running properly.
 * 
 * The content of the TCP byte streams are not consumed in any way, therefore is it the client connections'
 * responsibility to know the service they are consuming, and to handle all params, auth, SSL, keep-alives etc.
 * 
 * @author Errol Alpay
 *
 */
public class ProxyingTcpServer extends Thread {

	private Selector m_selector = null;
	private ServerSocketChannel m_serverSocketChannel = null;
	private InetSocketAddress m_inboundSocketAddress = null;
	private InetSocketAddress m_outboundSocketAddress = null;
	private Map<SocketChannel, SocketChannel> m_indexedSocketChannelPairs = new HashMap<SocketChannel, SocketChannel>();

	// preallocate a readwrite buffer for performance
	private ByteBuffer m_readWriteBuffer = ByteBuffer.allocate( 0xFFFF );

	/**
	 * 
	 * @param inboundPortNumber
	 *            The local port to listen on
	 * @param outboundServerName
	 *            The outbound service to proxy to
	 * @param outboundPortNumber
	 *            The outbound service port to listen on
	 * @throws IOException
	 */
	public ProxyingTcpServer( int inboundPortNumber, String outboundServerName, int outboundPortNumber ) throws IOException {

		// set the thread name so we can positively identify and track it at run time using a profiler or debugger
		// do not use hard-coded strings for class names since they are not automatically refactorable
		super( ProxyingTcpServer.class.getSimpleName() );

		// create the inet address to bind to once the thread is running
		m_inboundSocketAddress = new InetSocketAddress( "localhost", inboundPortNumber );

		// create the inet address for outbound connections. doing this once to improve performance by eliminating the
		// DNS lookup at runtime.
		m_outboundSocketAddress = new InetSocketAddress( outboundServerName, outboundPortNumber );

		// TODO: caching the IP address of the outbound service assumes its IP address will not change. add a separate
		// thread to periodically refresh this address.

		// set as a non-daemon so that the application cannot terminate until this thread is properly stopped
		setDaemon( false );

	}
	public void run() {

		try {

			System.err.println( "Starting " + ProxyingTcpServer.class.getSimpleName() );

			// create a selector for managing all connections
			m_selector = Selector.open();

			// create a server socket channel for listening for new inbound connections
			m_serverSocketChannel = ServerSocketChannel.open();
			m_serverSocketChannel.socket().bind( m_inboundSocketAddress );
			m_serverSocketChannel.configureBlocking( false );
			m_serverSocketChannel.register( m_selector, SelectionKey.OP_ACCEPT );

			while ( isInterrupted() == false ) {

				// wait here (forever) for new keys that are ready
				if ( m_selector.select() == 0 ) {
					continue;
				}

				// get the keys that are ready and iterate through them
				Set<SelectionKey> readyKeys = m_selector.selectedKeys();
				for ( SelectionKey readyKey : readyKeys ) {

					SocketChannel theSocketChannelForThisKey = null;
					try {

						if ( readyKey.isValid() == false ) {
							// invalid key ?
							System.err.println( "Invalid key" );
							continue;
						}

						if ( readyKey.isAcceptable() ) {
							// an inbound connection is ready to accept

							ServerSocketChannel channel = ( (ServerSocketChannel) readyKey.channel() );
							if ( channel != m_serverSocketChannel ) {
								// the channel that is ready to accept from is not the same as our server socket
								// channel. this should never happen, and perhaps there are some shenanigans going
								// on? bail out.
								throw new IOException( "Server socket channel corrupt" );
							}

							// accept the inbound connection, and connect to the outbound service
							SocketChannel inboundChannel = null;
							SocketChannel outboundChannel = null;
							try {
								System.err.println( "Accepting inbound connection" );

								inboundChannel = channel.accept();
								inboundChannel.configureBlocking( false );
								inboundChannel.register( m_selector, SelectionKey.OP_READ );
								theSocketChannelForThisKey = inboundChannel;

								// connect to the outbound service
								System.err.println( "Connecting to outbound service" );
								outboundChannel = SocketChannel.open();
								outboundChannel.connect( m_outboundSocketAddress );
								while ( outboundChannel.finishConnect() == false ) {
								}

								outboundChannel.configureBlocking( false );
								outboundChannel.register( m_selector, SelectionKey.OP_READ );

							} catch ( IOException ex ) {
								// something broke while accepting the inbound and/or connecting to the outbound.
								// let it go and cleanup.
								throw new EOFException( "Inbound/outbound connection failure" );
							}

							if ( inboundChannel != null && outboundChannel != null ) {
								// index the inbound/outbound socket channels as pairs so they can be found quickly
								m_indexedSocketChannelPairs.put( inboundChannel, outboundChannel );
								m_indexedSocketChannelPairs.put( outboundChannel, inboundChannel );
							}

						} else if ( readyKey.isReadable() ) {
							// a connection (inbound or outbound) is ready to read from

							// get the readable channel, and find its counterpart
							SocketChannel readableChannel = (SocketChannel) readyKey.channel();
							if ( readableChannel == null || readableChannel.isConnected() == false ) {
								continue;
							}
							theSocketChannelForThisKey = readableChannel;

							SocketChannel companionChannel = m_indexedSocketChannelPairs.get( readableChannel );

							// read from the readable, and write to its companion
							if ( companionChannel != null && companionChannel.isConnected() ) {
								( (Buffer) m_readWriteBuffer ).clear();

								int bytesRead = readableChannel.read( m_readWriteBuffer );
								if ( bytesRead == -1 ) {
									// EOF. cleanup.
									throw new EOFException( "Closed connection" );
								}

								m_readWriteBuffer.flip();
								int bytesWritten = 0;
								while ( m_readWriteBuffer.hasRemaining() ) {
									bytesWritten += companionChannel.write( m_readWriteBuffer );
								}

								if ( bytesWritten == bytesRead ) {
									System.err.println( "Read some bytes, and wrote them properly" );
								} else {
									// we couldnt write the same number of bytes to the companion. somethings wrong.
									// cleanup.
									System.err.println( "Read some bytes, but couldn't write them" );
									throw new EOFException( "Read/write byte mismatch" );
								}
							}

						}

					} catch ( CancelledKeyException ex ) {
						// invalid key. let it go.
						System.err.println( "Invalid key" );
					} catch ( EOFException ex ) {

						System.err.println( "Closing connection pair" );

						// close the inbound and related outbound connections
						if ( theSocketChannelForThisKey != null ) {

							closeSocketChannel( theSocketChannelForThisKey );
							SocketChannel companionSocketChannel = m_indexedSocketChannelPairs.remove( theSocketChannelForThisKey );

							if ( companionSocketChannel != null ) {
								closeSocketChannel( companionSocketChannel );
								m_indexedSocketChannelPairs.remove( companionSocketChannel );
							}
						}
					}
				}

				readyKeys.clear();
			}

		} catch ( Exception ex ) {

			// we can only throw runtime exceptions from the Thread.run method
			throw new RuntimeException( ex );

		} finally {

			// this thread is exiting.

			System.err.println( "Exiting " + ProxyingTcpServer.class.getSimpleName() );

			// cleanup resources, and swallow exceptions here because this thread is exiting
			for ( SocketChannel channel : m_indexedSocketChannelPairs.keySet() ) {
				closeSocketChannel( channel );
				closeSocketChannel( m_indexedSocketChannelPairs.get( channel ) );
			}
			m_indexedSocketChannelPairs.clear();

			try {
				m_selector.close();
			} catch ( Exception ex ) {
			}

			try {
				m_serverSocketChannel.close();
			} catch ( Exception ex ) {
			}

			// notify all objects that are waiting for this thread.
			synchronized ( this ) {
				notifyAll();
			}
		}

	}
	private void closeSocketChannel( SocketChannel channel ) {

		try {
			channel.close();
			SelectionKey key = channel.keyFor( m_selector );
			if ( key != null ) {
				key.cancel();
			}
		} catch ( Exception ex ) {
			// swallow any exceptions since we are discarding the channel
		}
	}

}
