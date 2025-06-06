package jadx.core.dex.visitors.usage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import jadx.api.usage.IUsageInfoData;
import jadx.api.usage.IUsageInfoVisitor;
import jadx.core.clsp.ClspClass;
import jadx.core.clsp.ClspClassSource;
import jadx.core.dex.info.FieldInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.ICodeNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.utils.exceptions.JadxRuntimeException;

import static jadx.core.utils.Utils.notEmpty;

public class UsageInfo implements IUsageInfoData {
	private final RootNode root;

	private final UseSet<ClassNode, ClassNode> clsDeps = new UseSet<>();
	private final UseSet<ClassNode, ClassNode> clsUsage = new UseSet<>();
	private final UseSet<ClassNode, MethodNode> clsUseInMth = new UseSet<>();
	private final UseSet<FieldNode, MethodNode> fieldUsage = new UseSet<>();
	private final UseSet<MethodNode, MethodNode> mthUsage = new UseSet<>();

	public UsageInfo(RootNode root) {
		this.root = root;
	}

	@Override
	public void apply() {
		clsDeps.visit((cls, deps) -> cls.setDependencies(sortedList(deps)));
		clsUsage.visit((cls, deps) -> cls.setUseIn(sortedList(deps)));
		clsUseInMth.visit((cls, methods) -> cls.setUseInMth(sortedList(methods)));
		fieldUsage.visit((field, methods) -> field.setUseIn(sortedList(methods)));
		mthUsage.visit((mth, methods) -> mth.setUseIn(sortedList(methods)));
	}

	@Override
	public void applyForClass(ClassNode cls) {
		cls.setDependencies(sortedList(clsDeps.get(cls)));
		cls.setUseIn(sortedList(clsUsage.get(cls)));
		cls.setUseInMth(sortedList(clsUseInMth.get(cls)));
		for (FieldNode fld : cls.getFields()) {
			fld.setUseIn(sortedList(fieldUsage.get(fld)));
		}
		for (MethodNode mth : cls.getMethods()) {
			mth.setUseIn(sortedList(mthUsage.get(mth)));
		}
	}

	@Override
	public void visitUsageData(IUsageInfoVisitor visitor) {
		clsDeps.visit((cls, deps) -> visitor.visitClassDeps(cls, sortedList(deps)));
		clsUsage.visit((cls, deps) -> visitor.visitClassUsage(cls, sortedList(deps)));
		clsUseInMth.visit((cls, methods) -> visitor.visitClassUseInMethods(cls, sortedList(methods)));
		fieldUsage.visit((field, methods) -> visitor.visitFieldsUsage(field, sortedList(methods)));
		mthUsage.visit((mth, methods) -> visitor.visitMethodsUsage(mth, sortedList(methods)));
		visitor.visitComplete();
	}

	public void clsUse(ClassNode cls, ArgType useType) {
		processType(useType, depCls -> clsUse(cls, depCls));
	}

	public void clsUse(MethodNode mth, ArgType useType) {
		processType(useType, depCls -> clsUse(mth, depCls));
	}

	public void clsUse(ICodeNode node, ArgType useType) {
		Consumer<ClassNode> consumer;
		switch (node.getAnnType()) {
			case CLASS:
				ClassNode cls = (ClassNode) node;
				consumer = depCls -> clsUse(cls, depCls);
				break;
			case METHOD:
				MethodNode mth = (MethodNode) node;
				consumer = depCls -> clsUse(mth, depCls);
				break;
			case FIELD:
				FieldNode fld = (FieldNode) node;
				ClassNode fldCls = fld.getParentClass();
				consumer = depCls -> clsUse(fldCls, depCls);
				break;
			default:
				throw new JadxRuntimeException("Unexpected use type: " + node.getAnnType());
		}
		processType(useType, consumer);
	}

	public void clsUse(MethodNode mth, ClassNode useCls) {
		ClassNode parentClass = mth.getParentClass();
		clsUse(parentClass, useCls);
		if (parentClass != useCls) {
			// exclude class usage in self methods
			clsUseInMth.add(useCls, mth);
		}
	}

	public void clsUse(ClassNode cls, ClassNode depCls) {
		ClassNode topParentClass = cls.getTopParentClass();
		clsDeps.add(topParentClass, depCls.getTopParentClass());

		clsUsage.add(depCls, cls);
		clsUsage.add(depCls, topParentClass);
	}

	/**
	 * Add method usage: {@code useMth} occurrence found in {@code mth} code
	 */
	public void methodUse(MethodNode mth, MethodNode useMth) {
		clsUse(mth, useMth.getParentClass());
		mthUsage.add(useMth, mth);
		// implicit usage
		clsUse(mth, useMth.getReturnType());
		useMth.getMethodInfo().getArgumentsTypes().forEach(argType -> clsUse(mth, argType));
	}

	public void fieldUse(MethodNode mth, FieldNode useFld) {
		clsUse(mth, useFld.getParentClass());
		fieldUsage.add(useFld, mth);
		// implicit usage
		clsUse(mth, useFld.getType());
	}

	public void fieldUse(ICodeNode node, FieldInfo useFld) {
		FieldNode fld = root.resolveField(useFld);
		if (fld == null) {
			return;
		}
		switch (node.getAnnType()) {
			case CLASS:
				// TODO: support "field in class" usage?
				// now use field parent class for "class in class" usage
				clsUse((ClassNode) node, fld.getParentClass());
				break;
			case METHOD:
				fieldUse((MethodNode) node, fld);
				break;
		}
	}

	/**
	 * Visit all class nodes found in subtypes of the provided type.
	 */
	private void processType(ArgType type, Consumer<ClassNode> consumer) {
		if (type == null || type == ArgType.OBJECT) {
			return;
		}
		if (type.isArray()) {
			processType(type.getArrayRootElement(), consumer);
			return;
		}
		if (type.isObject()) {
			// TODO: support custom handlers via API
			ClspClass clsDetails = root.getClsp().getClsDetails(type);
			if (clsDetails != null && clsDetails.getSource() == ClspClassSource.APACHE_HTTP_LEGACY_CLIENT) {
				root.getGradleInfoStorage().setUseApacheHttpLegacy(true);
			}
			ClassNode clsNode = root.resolveClass(type);
			if (clsNode != null) {
				consumer.accept(clsNode);
			}
			List<ArgType> genericTypes = type.getGenericTypes();
			if (notEmpty(genericTypes)) {
				for (ArgType argType : genericTypes) {
					processType(argType, consumer);
				}
			}
			List<ArgType> extendTypes = type.getExtendTypes();
			if (notEmpty(extendTypes)) {
				for (ArgType extendType : extendTypes) {
					processType(extendType, consumer);
				}
			}
			ArgType wildcardType = type.getWildcardType();
			if (wildcardType != null) {
				processType(wildcardType, consumer);
			}
			// TODO: process 'outer' types (check TestOuterGeneric test)
		}
	}

	private static <T extends Comparable<T>> List<T> sortedList(Set<T> nodes) {
		if (nodes == null || nodes.isEmpty()) {
			return Collections.emptyList();
		}
		List<T> list = new ArrayList<>(nodes);
		Collections.sort(list);
		return list;
	}
}
