package matcher.mapping;

import matcher.mapping.MappingState.*;
import matcher.type.ClassInstance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

class EnigmaImpl {
	public static void read(Path dir, boolean reverse, FlatMappingVisitor mappingAcceptor) throws IOException {
		if (reverse) throw new UnsupportedOperationException(); // TODO: implement

		try (Stream<Path> stream = Files.find(dir,
				Integer.MAX_VALUE,
				(path, attr) -> attr.isRegularFile() && path.getFileName().toString().endsWith(".mapping"),
				FileVisitOption.FOLLOW_LINKS)) {
			stream.forEach(file -> readFile(file, mappingAcceptor));
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}

		mappingAcceptor.visitEnd();
	}

	private static void readFile(Path file, FlatMappingVisitor mappingAcceptor) {
		try (BufferedReader reader = Files.newBufferedReader(file)) {
			String line;
			Context context = null;
			int indent = 0;
			StringBuilder commentSb = new StringBuilder();

			while ((line = reader.readLine()) != null) {
				if (line.isEmpty()) continue;

				int newIndent = 0;
				while (newIndent < line.length() && line.charAt(newIndent) == '\t') newIndent++;
				int indentChange = newIndent - indent;

				if (indentChange != 0) {
					if (commentSb.length() > 0) { // comment block terminated by indentation change
						applyComment(commentSb, context, mappingAcceptor);
						commentSb.setLength(0);
					}

					if (indentChange < 0) {
						for (int i = 0; i < -indentChange; i++) {
							context = context.getParent();
						}

						indent = newIndent;
					} else {
						throw new IOException("invalid enigma line (invalid indentation change): "+line);
					}
				}

				if (indent == line.length()) continue;

				line = line.substring(indent);
				String[] parts = line.split(" ");
				if (parts.length == 0) continue;

				if (commentSb.length() > 0 && !parts[0].equals("COMMENT")) { // comment block terminated by different attribute
					applyComment(commentSb, context, mappingAcceptor);
					commentSb.setLength(0);
				}

				switch (parts[0]) {
				case "CLASS": {
					if (parts.length < 2 || parts.length > 3) throw new IOException("invalid enigma line (missing/extra columns): "+line);

					String srcName = parts[1];

					if (context != null && !ClassInstance.hasOuterName(srcName)) { // inner class, recent Enigma doesn't include the outer names, restore it from context
						srcName = ClassInstance.getNestedName(((ClassContext) context).srcName, srcName);
					}

					String dstName;

					if (parts.length == 3) {
						dstName = parts[2];

						if (context != null && !ClassInstance.hasOuterName(dstName)) { // inner class, recent Enigma doesn't include the outer names, restore it from context
							dstName = ClassInstance.getNestedName(((ClassContext) context).dstName, dstName);
						}

						mappingAcceptor.visitClass(srcName, dstName);
					} else {
						dstName = srcName;
					}

					context = new ClassContext((ClassContext) context, srcName, dstName);
					indent++;

					break;
				}
				case "METHOD": {
					if (parts.length < 3 || parts.length > 4) throw new IOException("invalid enigma line (missing/extra columns): "+line);
					if (!parts[parts.length - 1].startsWith("(")) throw new IOException("invalid enigma line (invalid method desc): "+line);

					if (!(context instanceof ClassContext)) throw new IOException("invalid enigma line (method without class): "+line);
					ClassContext classContext = (ClassContext) context;

					if (parts.length == 4) {
						mappingAcceptor.visitMethod(classContext.srcName, parts[1], parts[3], null, parts[2], null);
					}

					context = new MemberContext(classContext, true, parts[1], parts[parts.length - 1]);
					indent++;

					break;
				}
				case "ARG":
				case "VAR": {
					if (parts.length != 3) throw new IOException("invalid enigma line (missing/extra columns): "+line);
					boolean isArg = parts[0].equals("ARG");

					if (!(context instanceof MemberContext)) throw new IOException("invalid enigma line (arg/var without method): "+line);
					MemberContext methodContext = (MemberContext) context;
					if (!methodContext.isMethod) throw new IOException("invalid enigma line (arg/var with field): "+line);
					ClassContext classContext = methodContext.parent;

					int lvIndex = Integer.parseInt(parts[1]);
					String name = parts[2];

					if (isArg) {
						mappingAcceptor.visitMethodArg(classContext.srcName, methodContext.name, methodContext.desc, -1, lvIndex, null, null, null, null, name);
					} else {
						mappingAcceptor.visitMethodVar(classContext.srcName, methodContext.name, methodContext.desc, -1, lvIndex, -1, null, null, null, null, name);
					}

					context = new VarContext(methodContext, isArg, lvIndex);
					indent++;

					break;
				}
				case "FIELD":
					if (parts.length < 3 || parts.length > 4) throw new IOException("invalid enigma line (missing/extra columns): "+line);

					if (!(context instanceof ClassContext)) throw new IOException("invalid enigma line (field without class): "+line);
					ClassContext classContext = (ClassContext) context;

					if (parts.length == 4) {
						mappingAcceptor.visitField(classContext.srcName, parts[1], parts[3], null, parts[2], null);
					}

					context = new MemberContext(classContext, false, parts[1], parts[parts.length - 1]);
					indent++;

					break;
				case "COMMENT":
					if (context == null) throw new IOException("invalid enigma line (comment without class/member): "+line);
					if (commentSb.length() > 0) commentSb.append('\n');

					if (line.length() > "COMMENT".length() + 1) {
						parseComment(line, "COMMENT".length() + 1, commentSb);
					}

					break;
				default:
					throw new IOException("invalid enigma line (unknown type): "+line);
				}
			}

			if (commentSb.length() > 0) { // comment block terminated by eof
				applyComment(commentSb, context, mappingAcceptor);
				commentSb.setLength(0);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void parseComment(String line, int offset, StringBuilder out) throws IOException {
		final int len = line.length();
		int pos;

		while ((pos = line.indexOf('\\', offset)) >= 0) {
			if (pos > offset) out.append(line, offset, pos - 1);
			pos++;
			if (pos == len) throw new IOException("invalid escape sequence: \\ at eol");

			char c = line.charAt(pos);
			int idx = escaped.indexOf(c);
			if (idx < 0) throw new IOException("invalid escape sequence: \\"+c);
			out.append(toEscape.charAt(idx));

			offset = pos + 1;
		}

		if (offset < len) out.append(line, offset, len);
	}

	private static void applyComment(CharSequence comment, Context context, FlatMappingVisitor mappingAcceptor) {
		String commentStr = comment.toString();

		if (context instanceof ClassContext) {
			ClassContext ctx = (ClassContext) context;
			mappingAcceptor.visitClassComment(ctx.srcName, (String) null, commentStr);
		} else if (context instanceof MemberContext) {
			MemberContext ctx = (MemberContext) context;

			if (ctx.isMethod) {
				mappingAcceptor.visitMethodComment(ctx.parent.srcName, ctx.name, ctx.desc,
						commentStr);
			} else {
				mappingAcceptor.visitFieldComment(ctx.parent.srcName, ctx.name, ctx.desc,
						(String) null, null, null,
						commentStr);
			}
		} else if (context instanceof VarContext) {
			VarContext ctx = (VarContext) context;

			if (ctx.isArg) {
				mappingAcceptor.visitMethodArgComment(ctx.parent.parent.srcName, ctx.parent.name, ctx.parent.desc, -1, ctx.lvIndex, null,
						(String) null, null, null, null,
						commentStr);
			} else {
				mappingAcceptor.visitMethodVarComment(ctx.parent.parent.srcName, ctx.parent.name, ctx.parent.desc, -1, ctx.lvIndex, -1, null,
						(String) null, null, null, null,
						commentStr);
			}
		} else {
			throw new IllegalStateException();
		}
	}

	private interface Context {
		Context getParent();
	}

	private static final class ClassContext implements Context {
		ClassContext(ClassContext parent, String srcName, String dstName) {
			this.parent = parent;
			this.srcName = srcName;
			this.dstName = dstName;
		}

		@Override
		public ClassContext getParent() {
			return parent;
		}

		final ClassContext parent;
		final String srcName;
		final String dstName;
	}

	private static final class MemberContext implements Context {
		MemberContext(ClassContext parent, boolean isMethod, String name, String desc) {
			this.parent = parent;
			this.isMethod = isMethod;
			this.name = name;
			this.desc = desc;
		}

		@Override
		public ClassContext getParent() {
			return parent;
		}

		final ClassContext parent;
		final boolean isMethod;
		final String name;
		final String desc;
	}

	private static final class VarContext implements Context {
		VarContext(MemberContext parent, boolean isArg, int lvIndex) {
			this.parent = parent;
			this.isArg = isArg;
			this.lvIndex = lvIndex;
		}

		@Override
		public MemberContext getParent() {
			return parent;
		}

		final MemberContext parent;
		final boolean isArg;
		final int lvIndex;
	}

	public static void write(Path file, MappingState state) throws IOException {
		file = file.toAbsolutePath();

		for (ClassMappingState clsState : state.classMap.values()) {
			if (ClassInstance.hasOuterName(clsState.name)) continue;

			String name = clsState.mappedName != null ? clsState.mappedName : clsState.name;
			Path path = file.resolve(name+".mapping").toAbsolutePath();
			if (!path.startsWith(file)) throw new RuntimeException("invalid mapped name: "+name);

			Files.createDirectories(path.getParent());

			try (Writer writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
				writeClass(clsState, "", writer);
			}
		}
	}

	private static void writeClass(ClassMappingState clsState, String prefix, Writer writer) throws IOException {
		writer.write(prefix);
		writer.write("CLASS ");
		writer.write(ClassInstance.getInnerName(clsState.name));

		if (clsState.mappedName != null) {
			writer.write(' ');
			writer.write(ClassInstance.getInnerName(clsState.mappedName));
		}

		writer.write('\n');

		if (clsState.comment != null) {
			writeComment(clsState.comment, prefix, "\t", writer);
		}

		if (!clsState.fieldMap.isEmpty()) {
			List<FieldMappingState> fields = new ArrayList<>(clsState.fieldMap.values());
			fields.sort(fieldComparator);

			for (FieldMappingState field : fields) {
				writer.write(prefix);
				writer.write("\tFIELD ");
				writer.write(field.name);

				if (field.mappedName != null) {
					writer.write(' ');
					writer.write(field.mappedName);
				}

				writer.write(' ');
				writer.write(field.desc);
				writer.write('\n');

				if (field.comment != null) {
					writeComment(field.comment, prefix, "\t\t", writer);
				}
			}
		}

		if (!clsState.methodMap.isEmpty()) {
			List<MethodMappingState> methods = new ArrayList<>(clsState.methodMap.values());
			methods.sort(methodComparator);

			for (MethodMappingState method : methods) {
				writer.write(prefix);
				writer.write("\tMETHOD ");
				writer.write(method.name);

				if (method.mappedName != null) {
					writer.write(' ');
					writer.write(method.mappedName);
				}

				writer.write(' ');
				writer.write(method.desc);
				writer.write('\n');

				if (method.comment != null) {
					writeComment(method.comment, prefix, "\t\t", writer);
				}

				if (!method.argMap.isEmpty()) {
					List<ArgMappingState> args = new ArrayList<>(method.argMap.values());
					args.sort(argComparator);

					for (ArgMappingState arg : args) {
						writer.write(prefix);
						writer.write("\t\tARG ");
						writer.write(Integer.toString(arg.lvIndex));

						if (arg.mappedName != null) {
							writer.write(' ');
							writer.write(arg.mappedName);
						}

						writer.write('\n');

						if (arg.comment != null) {
							writeComment(arg.comment, prefix, "\t\t\t", writer);
						}
					}
				}

				if (!method.vars.isEmpty()) {
					List<VarMappingState> vars = new ArrayList<>(method.vars);
					vars.sort(varComparator);

					for (VarMappingState var : vars) {
						writer.write(prefix);
						writer.write("\t\tVAR ");
						writer.write(Integer.toString(var.asmIndex));

						if (var.mappedName != null) {
							writer.write(' ');
							writer.write(var.mappedName);
						}

						writer.write('\n');

						if (var.comment != null) {
							writeComment(var.comment, prefix, "\t\t\t", writer);
						}
					}
				}
			}
		}

		if (!clsState.innerClasses.isEmpty()) {
			List<ClassMappingState> classes = new ArrayList<>(clsState.innerClasses);
			classes.sort(classComparator);

			for (ClassMappingState innerCls : classes) {
				writeClass(innerCls, prefix.concat("\t"), writer);
			}
		}
	}

	private static void writeComment(String comment, String prefixA, String prefixB, Writer writer) throws IOException {
		int start = 0;
		int pos;

		while ((pos = comment.indexOf('\n', start)) >= 0) {
			writeCommentLine(comment, start, pos, prefixA, prefixB, writer);
			start = pos + 1;
		}

		if (start < comment.length()) {
			writeCommentLine(comment, start, comment.length(), prefixA, prefixB, writer);
		}
	}

	private static void writeCommentLine(String comment, int start, int end, String prefixA, String prefixB, Writer writer) throws IOException {
		writer.write(prefixA);
		writer.write(prefixB);
		writer.write("COMMENT");

		if (end > start) {
			writer.write(' ');

			for (int i = start; i < end; i++) {
				char c = comment.charAt(i);
				int idx = toEscape.indexOf(c);

				if (idx >= 0) {
					if (i > start) writer.write(comment, start, i - start);
					writer.write('\\');
					writer.write(escaped.charAt(idx));
					start = i + 1;
				}
			}

			if (start < end) writer.write(comment, start, end - start);
		}

		writer.write('\n');
	}

	private static final Comparator<ClassMappingState> classComparator = new Comparator<ClassMappingState>() {
		@Override
		public int compare(ClassMappingState a, ClassMappingState b) {
			if (a.name.length() != b.name.length()) {
				return Integer.compare(a.name.length(), b.name.length());
			} else {
				return a.name.compareTo(b.name);
			}
		}
	};

	private static final Comparator<MethodMappingState> methodComparator = new Comparator<MethodMappingState>() {
		@Override
		public int compare(MethodMappingState a, MethodMappingState b) {
			return a.name.concat(a.desc).compareTo(b.name.concat(b.desc));
		}
	};

	private static final Comparator<FieldMappingState> fieldComparator = new Comparator<FieldMappingState>() {
		@Override
		public int compare(FieldMappingState a, FieldMappingState b) {
			return a.name.concat(a.desc).compareTo(b.name.concat(b.desc));
		}
	};

	private static final Comparator<ArgMappingState> argComparator = new Comparator<ArgMappingState>() {
		@Override
		public int compare(ArgMappingState a, ArgMappingState b) {
			return Integer.compare(a.lvIndex, b.lvIndex);
		}
	};

	private static final Comparator<VarMappingState> varComparator = new Comparator<VarMappingState>() {
		@Override
		public int compare(VarMappingState a, VarMappingState b) {
			return Integer.compare(a.asmIndex, b.asmIndex);
		}
	};

	private static final String toEscape = "\\\n\r\0\t";
	private static final String escaped = "\\nr0t";
}
