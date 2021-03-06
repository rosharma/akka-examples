package cs4r.labs.akka.examples.pingpong;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Random;
import java.util.concurrent.CompletionStage;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.BidiFlow;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Framing;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.Tcp;
import akka.stream.javadsl.Tcp.IncomingConnection;
import akka.stream.javadsl.Tcp.OutgoingConnection;
import akka.stream.javadsl.Tcp.ServerBinding;
import akka.util.ByteString;

public class PingPong {

	private static final BidiFlow<Message, ByteString, ByteString, Message, NotUsed> CODEC = BidiFlow
			.fromFunctions(MessageEncoding::toBytes, MessageEncoding::fromBytes);

	private static final BidiFlow<ByteString, ByteString, ByteString, ByteString, NotUsed> FRAMING = Framing
			.simpleFramingProtocol(5);

	/**
	 * Use without parameters to start both client and server.
	 *
	 * Use parameters `server 0.0.0.0 6001` to start server listening on port
	 * 6001.
	 *
	 * Use parameters `client 127.0.0.1 6001` to start client connecting to
	 * server on 127.0.0.1:6001.
	 *
	 */
	public static void main(String[] args) throws IOException {

		if (args.length == 0) {
			ActorSystem system = ActorSystem.create("ClientAndServer");
			InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", 6000);
			server(system, serverAddress);
			client(system, serverAddress);
		} else {
			InetSocketAddress serverAddress;
			if (args.length == 3) {
				serverAddress = new InetSocketAddress(args[1], Integer.valueOf(args[2]));
			} else {
				serverAddress = new InetSocketAddress("127.0.0.1", 6000);
			}
			if (args[0].equals("server")) {
				ActorSystem system = ActorSystem.create("Server");
				server(system, serverAddress);
			} else if (args[0].equals("client")) {
				ActorSystem system = ActorSystem.create("Client");
				client(system, serverAddress);
			}
		}
	}

	public static void server(ActorSystem system, InetSocketAddress serverAddress) {
		final ActorMaterializer materializer = ActorMaterializer.create(system);

		final Sink<IncomingConnection, CompletionStage<Done>> handler = Sink.foreach(conn -> {
			System.out.println("Client connected from: " + conn.remoteAddress());

			BidiFlow<ByteString, Message, Message, ByteString, NotUsed> protocolStack = CODEC.atop(FRAMING).reversed();

			Flow<Message, Message, NotUsed> map = Flow.of(Message.class).map(m -> new Pong(((Ping) m).id));

			Flow<ByteString, ByteString, NotUsed> connectionHandler = protocolStack.join(map);

			conn.handleWith(connectionHandler, materializer);
		});

		final CompletionStage<ServerBinding> bindingFuture = Tcp.get(system)
				.bind(serverAddress.getHostString(), serverAddress.getPort()).to(handler).run(materializer);

		bindingFuture.whenComplete((binding, throwable) -> {
			System.out.println("Server started, listening on: " + binding.localAddress());
		});

		bindingFuture.exceptionally(e -> {
			System.err.println("Server could not bind to " + serverAddress + " : " + e.getMessage());
			system.terminate();
			return null;
		});

	}

	public static void client(ActorSystem system, InetSocketAddress serverAddress) {
		final ActorMaterializer materializer = ActorMaterializer.create(system);

		Source<Message, NotUsed> source = Source.from(() -> new Random().ints(1, 101).iterator()).take(100)
				.<Message> map(id -> new Ping(id));

		final BidiFlow<Message, ByteString, ByteString, Message, NotUsed> protocolStack = CODEC.atop(FRAMING);

		Flow<ByteString, ByteString, CompletionStage<OutgoingConnection>> outgoingConnection = Tcp.get(system)
				.outgoingConnection(serverAddress.getHostString(), serverAddress.getPort());

		Flow<Message, Message, NotUsed> join = protocolStack.join(outgoingConnection);

		Source<Message, NotUsed> reply = source.via(join);

		final Sink<Message, CompletionStage<Done>> sink = Sink.foreach(System.out::println);

		reply.toMat(sink, Keep.right()).run(materializer).whenComplete((sucess, failure) -> {

			if (failure != null) {
				System.out.println(failure.getMessage());
			}

			system.terminate();

		});
	}

}
