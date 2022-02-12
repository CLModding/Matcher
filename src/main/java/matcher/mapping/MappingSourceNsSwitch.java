package matcher.mapping;

import java.util.*;

/**
 * Adapter switching the source namespace with one of the destination namespaces.
 *
 * <p>This adapts e.g. "src -> dstA, dstB, dstC, ..." mappings to "dstB -> dstA, src, dstC, ..." with a pre-pass to
 * build a class map for descriptor remapping.
 *
 * <p>After gathering the class map, the implementation delays src-named visit* invocations until the replacement dst
 * name is known, then replays it with the adjusted names.
 */
public final class MappingSourceNsSwitch extends ForwardingMappingVisitor {
	public MappingSourceNsSwitch(MappingVisitor next, String newSourceNs) {
		super(next);

		this.newSourceNsName = newSourceNs;
	}

	@Override
	public Set<MappingFlag> getFlags() {
		if (passThrough) {
			return next.getFlags();
		} else {
			Set<MappingFlag> ret = EnumSet.noneOf(MappingFlag.class);
			ret.addAll(next.getFlags());
			ret.add(MappingFlag.NEEDS_MULTIPLE_PASSES);
			ret.add(MappingFlag.NEEDS_UNIQUENESS);

			return ret;
		}
	}

	@Override
	public void reset() {
		classMapReady = false;
		passThrough = false;
		classMap.clear();

		next.reset();
	}

	@Override
	public boolean visitHeader() {
		if (!classMapReady) return true;

		return next.visitHeader();
	}

	@Override
	public void visitNamespaces(String srcNamespace, List<String> dstNamespaces) {
		if (!classMapReady) {
			if (srcNamespace.equals(newSourceNsName)) {
				classMapReady = true;
				passThrough = true;
				relayHeaderOrMetadata = next.visitHeader();

				if (relayHeaderOrMetadata) next.visitNamespaces(srcNamespace, dstNamespaces);
			} else {
				newSourceNs = dstNamespaces.indexOf(newSourceNsName);
				if (newSourceNs < 0) throw new RuntimeException("invalid new source ns "+newSourceNsName+": not in "+dstNamespaces+" or "+srcNamespace);

				oldSourceNsName = srcNamespace;

				int count = dstNamespaces.size();
				dstNames = new String[count];
			}
		} else {
			relayHeaderOrMetadata = true; // if next.visitHeader didn't return true in visitHeader, visitNamespaces wouldn't have been called

			List<String> newDstNamespaces = new ArrayList<>(dstNamespaces);
			newDstNamespaces.set(newSourceNs, oldSourceNsName);
			next.visitNamespaces(newSourceNsName, newDstNamespaces);

			Set<MappingFlag> flags = next.getFlags();

			if (flags.contains(MappingFlag.NEEDS_DST_FIELD_DESC) || flags.contains(MappingFlag.NEEDS_DST_METHOD_DESC)) {
				dstDescs = new String[dstNamespaces.size()];
			} else {
				dstDescs = null;
			}
		}
	}

	@Override
	public void visitMetadata(String key, String value) {
		if (classMapReady && relayHeaderOrMetadata) next.visitMetadata(key, value);
	}

	@Override
	public boolean visitContent() {
		if (!classMapReady) return true;

		relayHeaderOrMetadata = true; // for in-content metadata

		return next.visitContent();
	}

	@Override
	public boolean visitClass(String srcName) {
		if (passThrough) return next.visitClass(srcName);

		this.srcName = srcName;

		return true;
	}

	@Override
	public boolean visitField(String srcName, String srcDesc) {
		assert classMapReady;
		if (passThrough) return next.visitField(srcName, srcDesc);

		this.srcName = srcName;
		this.srcDesc = srcDesc;

		return true;
	}

	@Override
	public boolean visitMethod(String srcName, String srcDesc) {
		assert classMapReady;
		if (passThrough) return next.visitMethod(srcName, srcDesc);

		this.srcName = srcName;
		this.srcDesc = srcDesc;

		return true;
	}

	@Override
	public boolean visitMethodArg(int argPosition,  int lvIndex, String srcName) {
		assert classMapReady;
		if (passThrough) return next.visitMethodArg(argPosition, lvIndex, srcName);

		this.srcName = srcName;
		this.argIdx = argPosition;
		this.lvIndex = lvIndex;

		return true;
	}

	@Override
	public boolean visitMethodVar(int lvtRowIndex, int lvIndex, int startOpIdx, String srcName) {
		assert classMapReady;
		if (passThrough) return next.visitMethodVar(lvtRowIndex, lvIndex, startOpIdx, srcName);

		this.srcName = srcName;
		this.argIdx = lvtRowIndex;
		this.lvIndex = lvIndex;
		this.startOpIdx = startOpIdx;

		return true;
	}

	@Override
	public boolean visitEnd() {
		if (!classMapReady) {
			classMapReady = true;
			return false;
		}

		return next.visitEnd();
	}

	@Override
	public void visitDstName(MappedElementKind targetKind, int namespace, String name) {
		if (!classMapReady) {
			if (namespace == newSourceNs) classMap.put(srcName, name);
			return;
		}

		if (passThrough) {
			next.visitDstName(targetKind, namespace, name);
			return;
		}

		if (namespace >= dstNames.length) throw new IllegalArgumentException("out of bounds namespace");

		dstNames[namespace] = name;
	}

	@Override
	public void visitDstDesc(MappedElementKind targetKind, int namespace, String desc) {
		if (passThrough) {
			next.visitDstDesc(targetKind, namespace, desc);
		} else if (classMapReady && dstDescs != null) {
			dstDescs[namespace] = desc;
		}
	}

	@Override
	public boolean visitElementContent(MappedElementKind targetKind) {
		if (!classMapReady) return false;
		if (passThrough) return next.visitElementContent(targetKind);

		String dstName = dstNames[newSourceNs];
		if (dstName == null) dstName = srcName;

		boolean relay;

		switch (targetKind) {
		case CLASS:
			relay = next.visitClass(dstName);
			break;
		case FIELD:
			relay = next.visitField(dstName, srcDesc != null ? MappingUtil.mapDesc(srcDesc, classMap) : null);
			break;
		case METHOD:
			relay = next.visitMethod(dstName, srcDesc != null ? MappingUtil.mapDesc(srcDesc, classMap) : null);
			break;
		case METHOD_ARG:
			relay = next.visitMethodArg(argIdx, lvIndex, dstName);
			break;
		case METHOD_VAR:
			relay = next.visitMethodVar(argIdx, lvIndex, startOpIdx, dstName);
			break;
		default:
			throw new IllegalStateException();
		}

		if (relay) {
			boolean sendDesc = dstDescs != null && srcDesc != null && (targetKind == MappedElementKind.FIELD || targetKind == MappedElementKind.METHOD);

			for (int i = 0; i < dstNames.length; i++) {
				if (i == newSourceNs) {
					next.visitDstName(targetKind, i, srcName);
					if (sendDesc) next.visitDstDesc(targetKind, i, srcDesc);
				} else {
					String name = dstNames[i];
					if (name != null) next.visitDstName(targetKind, i, name);

					if (sendDesc) {
						String desc = dstDescs[i];
						if (desc != null) next.visitDstDesc(targetKind, i, desc);
					}
				}
			}

			relay = next.visitElementContent(targetKind);
		}

		Arrays.fill(dstNames, null);
		if (dstDescs != null) Arrays.fill(dstDescs, null);

		return relay;
	}

	private final String newSourceNsName;

	private int newSourceNs;
	private String oldSourceNsName;

	private final Map<String, String> classMap = new HashMap<>();
	private boolean classMapReady;
	private boolean passThrough;
	private boolean relayHeaderOrMetadata;

	private String srcName;
	private String srcDesc;
	private int argIdx, lvIndex, startOpIdx;
	private String[] dstNames;
	private String[] dstDescs;
}
