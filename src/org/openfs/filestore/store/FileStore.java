package org.openfs.filestore.store;

import com.google.common.base.Preconditions;
import com.google.gson.GsonBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.openfs.filestore.Container;
import org.openfs.filestore.Reference;
import org.openfs.filestore.codec.decoder.ReferenceDecoder;
import org.openfs.filestore.codec.encoder.ReferenceEncoder;
import org.openfs.filestore.file.IndexedFile;
import org.openfs.filestore.store.codec.decoders.IdentifierDecoder;
import org.openfs.filestore.store.codec.decoders.NameDecoder;
import org.openfs.filestore.store.codec.decoders.PayloadDecoder;
import org.openfs.filestore.store.codec.encoders.EOFEncoder;
import org.openfs.filestore.store.codec.encoders.IdentifierEncoder;
import org.openfs.filestore.store.codec.encoders.NameEncoder;
import org.openfs.filestore.store.codec.encoders.PayloadEncoder;

import java.util.*;
import java.util.function.Predicate;

/**
 * @author Harrison, Alias: Hc747, Contact: harrisoncole05@gmail.com
 * @version 1.0
 * @since 6/2/17
 */
public final class FileStore extends Reference implements Container<IndexedFile, FileStore> {

	private static final Map<Integer, Class<? extends ReferenceDecoder<FileStore>>> decoders = new HashMap<>();
	private static final Deque<Class<? extends ReferenceEncoder<FileStore>>> encoders = new ArrayDeque<>();

	private final List<IndexedFile> files = new ArrayList<>();

	private FileStore() {}

	private FileStore(int id, String name) {
		super(id, name);
	}

	public static FileStore create(ByteBuf buffer) throws Exception {
		final FileStore store = new FileStore();
		store.decode(buffer);
		return store;
	}

	public static FileStore create(int id) {
		return create(id, DEFAULT_NAME);
	}

	public static FileStore create(int id, String name) {
		return new FileStore(id, name);
	}

	@Override
	public FileStore add(IndexedFile reference) {
		Preconditions.checkArgument(!contains(reference::equals), "You must invoke \'!contains\' on \'reference\' prior to invoking add.");
		reference.setIdentifier(files.size());
		files.add(reference);
		return this;
	}

	@Override
	public FileStore remove(Predicate<IndexedFile> lookup) {
		Preconditions.checkNotNull(lookup, "\'lookup\' not permitted to be null.");
		final IndexedFile reference = get(lookup);
		files.remove(reference);
		if (reference.getIdentifier() != size())
			sort();
		return this;
	}

	public FileStore remove(int identifier) {
		return remove(it -> it.getIdentifier() == identifier);
	}

	public FileStore remove(String name) {
		return remove(it -> it.getName().equals(name));
	}

	@Override
	public FileStore replace(Predicate<IndexedFile> lookup, IndexedFile replacement) {
		Preconditions.checkNotNull(replacement, "\'replacement\' not permitted to be null.");
		Preconditions.checkArgument(contains(lookup), "You must invoke \'contains\' on \'target\' prior to invoking \'replace\'.");
		final int index = files.indexOf(get(lookup));
		Preconditions.checkArgument(index >= 0, "\'index\' not permitted to be negative.");
		replacement.setIdentifier(index);
		files.set(index, replacement);
		return this;
	}

	public FileStore replace(int identifier, IndexedFile replacement) {
		return replace(it -> it.getIdentifier() == identifier, replacement);
	}

	public FileStore replace(String name, IndexedFile replacement) {
		return replace(it -> it.getName().equals(name), replacement);
	}

	@Override
	public FileStore insert(IndexedFile reference, int index) {
		Preconditions.checkNotNull(reference, "\'reference\' not permitted to be null.");
		Preconditions.checkArgument(index >= 0 && index <= files.size(), String.format("0 <= \'index\' (%d) <= size (%d).", index, files.size()));
		Preconditions.checkArgument(!contains(reference::equals), "You must invoke \'!contains\' on \'reference\' prior to invoking \'insert\'.");
		final ArrayList<IndexedFile> references = new ArrayList<>();
		references.add(reference);
		while (index < files.size()) {
			references.add(files.remove(index));
		}
		references.forEach(this::add);
		return this;
	}

	@Override
	public boolean contains(Predicate<IndexedFile> lookup) {
		Preconditions.checkNotNull(lookup, "\'lookup\' not permitted to be null.");
		return files.stream().anyMatch(lookup);
	}

	public boolean contains(int identifier) {
		return contains(it -> it.getIdentifier() == identifier);
	}

	public boolean contains(String name) {
		return contains(it -> it.getName().equals(name));
	}

	@Override
	public IndexedFile get(Predicate<IndexedFile> lookup) {
		final Optional<IndexedFile> idx = files.stream().filter(lookup).findAny();
		Preconditions.checkArgument(idx.isPresent(), "You must invoke \'contains\' prior to invoking \'get\'.");
		return idx.get();
	}

	public IndexedFile get(int id) {
		return get(it -> it.getIdentifier() == id);
	}

	public IndexedFile get(String name) {
		return get(it -> it.getName().equals(name));
	}

	@Override
	public boolean isEmpty() {
		return files.isEmpty() || files.stream().allMatch(IndexedFile::isEmpty);
	}

	@Override
	public int size() {
		return files.size();
	}

	@Override
	public ByteBuf encode() throws Exception {
		final ByteBuf buffer = Unpooled.buffer();

		encoders.forEach(it -> {
			try {
				it.newInstance().encode(this, buffer);
				System.out.println("\tFileStore encoded: "+it.getSimpleName());
			} catch (Exception e) {
				e.printStackTrace();
			}
		});

		return buffer;
	}

	@Override
	public void decode(ByteBuf buffer) throws Exception {
		for (;;) {
			final int opcode = buffer.readByte();
			if (opcode == EOF)
				break;
			final Class<? extends ReferenceDecoder<FileStore>> decoder = decoders.get(opcode);
			Preconditions.checkNotNull(decoder, String.format("Unhandled FileStore opcode: %d", opcode));
			decoder.newInstance().decode(this, buffer);
			System.out.println("\tFileStore decoded: "+decoder.getSimpleName());
		}
	}

	@Override
	public String toString() {
		return new GsonBuilder().setPrettyPrinting().create().toJson(this);
	}

	@Override
	public boolean equals(Object other) {
		final boolean equals = other instanceof FileStore && super.equals(other);
		return equals && files.equals(((FileStore)other).files);
	}

	private void sort() {
		for (int index = 0; index < files.size(); index++)
			files.get(index).setIdentifier(index);
	}

	public List<IndexedFile> getFiles() {
		return Collections.unmodifiableList(files);
	}

	private static void registerDecoder(int opcode, Class<? extends ReferenceDecoder<FileStore>> clazz) {
		final Class<? extends ReferenceDecoder<FileStore>> decoder = decoders.get(opcode);
		Preconditions.checkState(decoder == null, String.format("ReferenceDecoder opcode already handled: %d, %s", opcode, decoder.getSimpleName()));
		decoders.put(opcode, clazz);
	}

	private static void registerEncoder(Class<? extends ReferenceEncoder<FileStore>> encoder) {
		Preconditions.checkArgument(!encoders.contains(encoder), String.format("ReferenceEncoder already handled: %s", encoder.getSimpleName()));
		encoders.addLast(encoder);
	}

	static {
		registerDecoder(REFERENCE_IDENTIFIER_OPCODE, IdentifierDecoder.class);
		registerDecoder(REFERENCE_NAME_OPCODE, NameDecoder.class);
		registerDecoder(REFERENCE_PAYLOAD_OPCODE, PayloadDecoder.class);

		registerEncoder(IdentifierEncoder.class);
		registerEncoder(NameEncoder.class);
		registerEncoder(PayloadEncoder.class);
		registerEncoder(EOFEncoder.class);
	}

}
