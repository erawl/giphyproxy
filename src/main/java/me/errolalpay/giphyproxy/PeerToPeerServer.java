package me.errolalpay.giphyproxy;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Joins two TCP connections in a peer-to-peer conversation.
 * 
 * Principal of operation: This abstract server class listens for inbound TCP connections like typical TCP servers do.
 * Once an inbound connection is accepted, the service then looks for a peer to connect it with. Peers are located by
 * subclasses, and not by this server class directly. The two peers are then indexed together, forwards and backwards,
 * in a HashMap such that each peer can be found, given the other, with predictable performance. Whenever data is read
 * from one connection, its peer connection is located, and that same data is then written to the peer. This "read from
 * connection, write to peer" works in both directions, regardless of which connection in the 2-peer arrangement sent
 * the data. The TCP conversation continues until either peer closes itself. At that time, the server assumes the
 * conversation is over, and destroys both peers as well as their membership in the index.
 * 
 * This server class has an emphasis on privacy, therefore it doesn't log ip addresses, number of bytes written,
 * date/time, etc. It limits logging to only that which helps us see the server is running properly.
 * 
 * @author Errol Alpay
 */
public abstract class PeerToPeerServer extends Thread {

	private Selector m_selector = null;
	private ServerSocketChannel m_serverSocketChannel = null;
	private InetSocketAddress m_inboundSocketAddress = null;
	private Map<SocketChannel, SocketChannel> m_indexedPeers = new HashMap<SocketChannel, SocketChannel>();

	// preallocate a readwrite buffer for performance
	private ByteBuffer m_sharedReadWriteBuffer = ByteBuffer.allocate( 0xFFFF );

	/**
	 * 
	 * @param portNumber
	 *            The local port to listen on
	 * 
	 * @throws IOException
	 */
	protected PeerToPeerServer( int portNumber ) throws IOException {
		super();

		// create the inet address to bind to once the thread is running
		m_inboundSocketAddress = new InetSocketAddress( "localhost", portNumber );

		// set as a non-daemon so that the application cannot terminate until this thread is properly stopped
		setDaemon( false );

		// set the thread name so we can positively identify and track it at run time using a profiler or debugger.
		// do not use hard-coded strings for class names since they are not automatically refactorable
		setName( getClass().getSimpleName() );

	}
	public void run() {

		try {

			System.err.println( "Starting " + getClass().getSimpleName() );

			// create a selector for managing all connections
			m_selector = Selector.open();

			// create a server socket channel for listening for new inbound connections
			m_serverSocketChannel = ServerSocketChannel.open();
			m_serverSocketChannel.socket().bind( m_inboundSocketAddress );
			m_serverSocketChannel.configureBlocking( false );
			m_serverSocketChannel.register( m_selector, SelectionKey.OP_ACCEPT );

			// notify all objects that are waiting for this thread.
			synchronized ( this ) {
				notifyAll();
			}

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

							if ( ( (ServerSocketChannel) readyKey.channel() ) != m_serverSocketChannel ) {
								// the channel that is ready to accept from is not the same as our server socket
								// channel. this should never happen, and perhaps there are some shenanigans going
								// on? bail out.
								throw new IOException( "Server socket channel corrupt" );
							}

							// accept the inbound connection
							SocketChannel acceptedChannel = null;
							SocketChannel peerChannel = null;
							try {
								System.err.println( "Accepting inbound connection" );

								acceptedChannel = m_serverSocketChannel.accept();
								acceptedChannel.configureBlocking( false );
								acceptedChannel.register( m_selector, SelectionKey.OP_READ );
								theSocketChannelForThisKey = acceptedChannel;

								// locate the peer for this channel
								peerChannel = locatePeer( acceptedChannel );
								if ( peerChannel == null || peerChannel.isConnected() == false ) {
									throw new EOFException( "Peering failure" );
								}

								peerChannel.configureBlocking( false );
								peerChannel.register( m_selector, SelectionKey.OP_READ );

							} catch ( IOException ex ) {
								// something broke while accepting the inbound and/or finding the peer
								// let it go and cleanup.
								throw new EOFException( "Inbound/peer connection failure" );
							}

							if ( acceptedChannel != null && peerChannel != null ) {
								// index the peer socket channels as pairs so they can be found quickly
								m_indexedPeers.put( acceptedChannel, peerChannel );
								m_indexedPeers.put( peerChannel, acceptedChannel );
							}

						} else if ( readyKey.isReadable() ) {
							// a connection is ready to read from

							// get the readable channel, and find its peer
							SocketChannel readableChannel = (SocketChannel) readyKey.channel();
							if ( readableChannel == null || readableChannel.isConnected() == false ) {
								throw new EOFException( "Readable/peer processing failure" );
							}
							theSocketChannelForThisKey = readableChannel;

							SocketChannel peerChannel = m_indexedPeers.get( readableChannel );
							if ( peerChannel == null || peerChannel.isConnected() == false ) {
								throw new EOFException( "Readable/peer processing failure" );
							}

							// read from the readable, and write to its peer
							( (Buffer) m_sharedReadWriteBuffer ).clear();

							int bytesRead = readableChannel.read( m_sharedReadWriteBuffer );
							if ( bytesRead == -1 ) {
								// EOF. cleanup.
								throw new EOFException( "Closed connection" );
							}

							( (Buffer) m_sharedReadWriteBuffer ).flip();
							int bytesWritten = 0;
							while ( m_sharedReadWriteBuffer.hasRemaining() ) {
								bytesWritten += peerChannel.write( m_sharedReadWriteBuffer );
							}

							if ( bytesWritten == bytesRead ) {
								System.err.println( "Read some bytes, and wrote them properly" );
							} else {
								// we couldnt write the same number of bytes to the peer. somethings wrong.
								// cleanup.
								System.err.println( "Read some bytes, but couldn't write them" );
								throw new EOFException( "Read/write byte mismatch" );
							}
						}

					} catch ( CancelledKeyException ex ) {
						// invalid key. let it go.
						System.err.println( "Invalid key" );
					} catch ( EOFException ex ) {

						System.err.println( "Closing peers" );

						// close the peers
						if ( theSocketChannelForThisKey != null ) {

							closeSocketChannel( theSocketChannelForThisKey );
							SocketChannel peerSocketChannel = m_indexedPeers.remove( theSocketChannelForThisKey );

							if ( peerSocketChannel != null ) {
								closeSocketChannel( peerSocketChannel );
								m_indexedPeers.remove( peerSocketChannel );
							}
						}
					}
				}

				readyKeys.clear();
			}

		} catch ( Exception ex ) {

			// we can only throw runtime exceptions from the Thread.run method
			System.err.println( ex.getMessage() );

			throw new RuntimeException( ex );

		} finally {

			// this thread is exiting.

			System.err.println( "Exiting " + getClass().getSimpleName() );

			// cleanup resources, and swallow exceptions here because this thread is exiting
			for ( SocketChannel channel : m_indexedPeers.keySet() ) {
				closeSocketChannel( channel );
				closeSocketChannel( m_indexedPeers.get( channel ) );
			}
			m_indexedPeers.clear();

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
	/**
	 * Stops the server
	 */
	public void shutdown() {
		interrupt();
	}
	protected void closeSocketChannel( SocketChannel channel ) {

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

	/**
	 * Locates the peer for the given server socket channel
	 * 
	 * @param channel
	 *            The channel to find a peer for
	 * @return
	 * @throws IOException
	 */
	protected abstract SocketChannel locatePeer( SocketChannel channel ) throws IOException;

}
