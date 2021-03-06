package cs4r.labs.akka.examples.quixote;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.ActorMaterializer;
import akka.stream.IOResult;
import akka.stream.javadsl.BidiFlow;
import akka.stream.javadsl.FileIO;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Framing;
import akka.stream.javadsl.FramingTruncation;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.Tcp;
import akka.stream.javadsl.Tcp.IncomingConnection;
import akka.stream.javadsl.Tcp.OutgoingConnection;
import akka.stream.javadsl.Tcp.ServerBinding;
import akka.util.ByteString;

public class Quixote {

	private static final BidiFlow<String, ByteString, ByteString, Integer, NotUsed> SERVER_CODEC = BidiFlow
			.fromFunctions(QuixoteEnconding::stringToBytes, QuixoteEnconding::integerFromBytes);

	private static final BidiFlow<Integer, ByteString, ByteString, String, NotUsed> CLIENT_CODEC = BidiFlow
			.fromFunctions(QuixoteEnconding::integerToBytes, QuixoteEnconding::stringFromBytes);

	private static final BidiFlow<ByteString, ByteString, ByteString, ByteString, NotUsed> FRAMING = Framing
			.simpleFramingProtocol(100_000);

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

	private static void server(ActorSystem system, InetSocketAddress serverAddress) {

		final ActorMaterializer materializer = ActorMaterializer.create(system);

		final Sink<IncomingConnection, CompletionStage<Done>> connectionHandler = Sink.foreach(conn -> {
			System.out.println("Client connected from: " + conn.remoteAddress());

			BidiFlow<ByteString, Integer, String, ByteString, NotUsed> protocolStack = SERVER_CODEC.atop(FRAMING)
					.reversed();

			Flow<Integer, String, NotUsed> integerToString = Flow.of(Integer.class).map(i -> {
				return numberToQuixoteLine(materializer, i);
			}).map(s -> {
				System.out.println("Server sends to client: " + s);
				return s;
			});

			Flow<ByteString, ByteString, NotUsed> frameHandler = protocolStack.join(integerToString);

			conn.handleWith(frameHandler, materializer);
		});

		final CompletionStage<ServerBinding> bindingFuture = Tcp.get(system)
				.bind(serverAddress.getHostString(), serverAddress.getPort()).to(connectionHandler).run(materializer);

		bindingFuture.whenComplete((binding, throwable) -> {
			System.out.println("Server started, listening on: " + binding.localAddress());
		});

		bindingFuture.exceptionally(e -> {
			System.err.println("Server could not bind to " + serverAddress + " : " + e.getMessage());
			system.terminate();
			return null;
		});

	}

	private static String numberToQuixoteLine(final ActorMaterializer materializer, Integer i) {
		System.out.println("Server received from client: " + i);

		final CompletionStage<Pair<String, Integer>> result = getIthLineOfQuixote(materializer, i);

		CompletableFuture<Pair<String, Integer>> completableFuture = result.toCompletableFuture();

		try {
			return completableFuture.get(1, TimeUnit.MINUTES).first();
		} catch (Exception e) {
			return "###### FAILED ####### ";
		}
	}

	private static CompletionStage<Pair<String, Integer>> getIthLineOfQuixote(final ActorMaterializer materializer,
			Integer i) {

		File arg0 = Paths.get("quixote.txt").toFile();

		final Source<String, CompletionStage<IOResult>> lines = FileIO.fromFile(arg0)
				.via(Framing.delimiter(ByteString.fromString("\r\n"), 100, FramingTruncation.ALLOW))
				.map(b -> b.utf8String()).map(s -> s.trim()).filter(s -> s.length() > 1);

		final Source<Integer, NotUsed> integers = Source.range(0, Integer.MAX_VALUE - 1);

		final Source<Pair<String, Integer>, CompletionStage<IOResult>> zip = lines.zip(integers);

		return zip.filter(p -> p.second().equals(i)).runWith(Sink.head(), materializer);
	}

	private static void client(ActorSystem system, InetSocketAddress serverAddress) {
		final ActorMaterializer materializer = ActorMaterializer.create(system);

		Source<Integer, NotUsed> oneHundredRandomIntsToSend = Source
				.fromIterator(() -> new Random().ints(0, 10_000).limit(100).boxed().iterator()).map(i -> {
					System.out.println("Client sends to the server :" + i);
					return i;
				});

		final BidiFlow<Integer, ByteString, ByteString, String, NotUsed> protocolStack = CLIENT_CODEC.atop(FRAMING);

		Flow<ByteString, ByteString, CompletionStage<OutgoingConnection>> outgoingConnection = Tcp.get(system)
				.outgoingConnection(serverAddress.getHostString(), serverAddress.getPort());

		Flow<Integer, String, NotUsed> join = protocolStack.join(outgoingConnection);

		Source<String, NotUsed> reply = oneHundredRandomIntsToSend.via(join);

		final Sink<String, CompletionStage<Done>> sink = Sink.foreach(e -> {
			System.out.println("Client received from server: " + e);
		});

		reply.toMat(sink, Keep.right()).run(materializer).whenComplete((sucess, failure) -> {

			if (failure != null) {
				System.out.println(failure.getMessage());
			}

			system.terminate();

		});
	}
}
