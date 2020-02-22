package org.pitest.mutationtest.build.intercept.equivalent;

import static org.pitest.bytecode.analysis.InstructionMatchers.anyInstruction;
import static org.pitest.bytecode.analysis.InstructionMatchers.isInstruction;
import static org.pitest.bytecode.analysis.InstructionMatchers.methodCallNamed;
import static org.pitest.bytecode.analysis.InstructionMatchers.notAnInstruction;
import static org.pitest.bytecode.analysis.InstructionMatchers.opCode;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.pitest.bytecode.analysis.ClassTree;
import org.pitest.bytecode.analysis.MethodMatchers;
import org.pitest.bytecode.analysis.MethodTree;
import org.pitest.functional.FCollection;
import org.pitest.mutationtest.build.CompoundMutationInterceptor;
import org.pitest.mutationtest.build.InterceptorParameters;
import org.pitest.mutationtest.build.InterceptorType;
import org.pitest.mutationtest.build.MutationInterceptor;
import org.pitest.mutationtest.build.MutationInterceptorFactory;
import org.pitest.mutationtest.engine.Mutater;
import org.pitest.mutationtest.engine.MutationDetails;
import org.pitest.mutationtest.engine.gregor.mutators.BooleanFalseReturnValsMutator;
import org.pitest.mutationtest.engine.gregor.mutators.BooleanTrueReturnValsMutator;
import org.pitest.mutationtest.engine.gregor.mutators.EmptyObjectReturnValsMutator;
import org.pitest.mutationtest.engine.gregor.mutators.NullReturnValsMutator;
import org.pitest.mutationtest.engine.gregor.mutators.PrimitiveReturnsMutator;
import org.pitest.plugin.Feature;
import org.pitest.sequence.Context;
import org.pitest.sequence.Match;
import org.pitest.sequence.QueryParams;
import org.pitest.sequence.QueryStart;
import org.pitest.sequence.SequenceMatcher;
import org.pitest.sequence.Slot;

/**
 * Tightly coupled to the PrimitiveReturnsMutator and EmptyObjectReturnValsMutator
 *   - removes trivially equivalent mutants generated by these.
 * operator
 *
 */
public class EquivalentReturnMutationFilter implements MutationInterceptorFactory {
    
  @Override
  public String description() {
    return "Trivial return vals equivalence filter";
  }

  @Override
  public Feature provides() {
    return Feature.named("FRETEQUIV")
        .withOnByDefault(true)
        .withDescription("Filters return vals mutants with bytecode equivalent to the unmutated class");

  }

  @Override
  public MutationInterceptor createInterceptor(InterceptorParameters params) {
    return new CompoundMutationInterceptor(Arrays.asList(new PrimitiveEquivalentFilter(),
        new NullReturnsFilter(),
        new EmptyReturnsFilter(),
        new HardCodedTrueEquivalentFilter())) {
      @Override
      public InterceptorType type() {
        return InterceptorType.FILTER;
      }
    };
  }

}

class HardCodedTrueEquivalentFilter implements MutationInterceptor {   
  private static final Slot<AbstractInsnNode> MUTATED_INSTRUCTION = Slot.create(AbstractInsnNode.class);
  
  static final SequenceMatcher<AbstractInsnNode> BOXED_TRUE = QueryStart
      .any(AbstractInsnNode.class)
      .zeroOrMore(QueryStart.match(anyInstruction()))
      .then(opCode(Opcodes.ICONST_1))
      .then(methodCallNamed("valueOf"))
      .then(isInstruction(MUTATED_INSTRUCTION.read()))
      .zeroOrMore(QueryStart.match(anyInstruction()))
      .compile(QueryParams.params(AbstractInsnNode.class)
          .withIgnores(notAnInstruction())
          );

  private static final Set<String> MUTATOR_IDS = new HashSet<>();

  static {
     MUTATOR_IDS.add(BooleanTrueReturnValsMutator.BOOLEAN_TRUE_RETURN.getGloballyUniqueId());
  }

  private ClassTree currentClass;

  @Override
  public InterceptorType type() {
    return InterceptorType.FILTER;
  }

  @Override
  public void begin(ClassTree clazz) {
    this.currentClass = clazz;
  }

  @Override
  public Collection<MutationDetails> intercept(
      Collection<MutationDetails> mutations, Mutater m) {
    return FCollection.filter(mutations, isEquivalent(m).negate());
  }

  private Predicate<MutationDetails> isEquivalent(Mutater m) {
    return new Predicate<MutationDetails>() {
      @Override
      public boolean test(MutationDetails a) {
        if (!MUTATOR_IDS.contains(a.getMutator())) {
          return false;
        }
        final int instruction = a.getInstructionIndex();
        final MethodTree method = HardCodedTrueEquivalentFilter.this.currentClass.methods().stream()
            .filter(MethodMatchers.forLocation(a.getId().getLocation()))
            .findFirst().get();
        return primitiveTrue(instruction, method) || boxedTrue(instruction, method);
      }

      private boolean primitiveTrue(int instruction, MethodTree method) {
        return method.realInstructionBefore(instruction).getOpcode() == Opcodes.ICONST_1;
      }

      private boolean boxedTrue(int instruction, MethodTree method) {
          final Context<AbstractInsnNode> context = Context.start(method.instructions(), false);
          context.store(MUTATED_INSTRUCTION.write(), method.instruction(instruction));
          return BOXED_TRUE.matches(method.instructions(), context);         
      }
    };
  }

  @Override
  public void end() {
    this.currentClass = null;
  }
}


class PrimitiveEquivalentFilter implements MutationInterceptor {

  private static final Set<String> MUTATOR_IDS = new HashSet<>();
  private static final Set<Integer> ZERO_CONSTANTS = new HashSet<>();
  static {
    ZERO_CONSTANTS.add(Opcodes.ICONST_0);
    ZERO_CONSTANTS.add(Opcodes.LCONST_0);
    ZERO_CONSTANTS.add(Opcodes.FCONST_0);
    ZERO_CONSTANTS.add(Opcodes.DCONST_0);

    MUTATOR_IDS.add(PrimitiveReturnsMutator.PRIMITIVE_RETURN_VALS_MUTATOR.getGloballyUniqueId());
    MUTATOR_IDS.add(BooleanFalseReturnValsMutator.BOOLEAN_FALSE_RETURN.getGloballyUniqueId());
  }

  private ClassTree currentClass;

  @Override
  public InterceptorType type() {
    return InterceptorType.FILTER;
  }

  @Override
  public void begin(ClassTree clazz) {
    this.currentClass = clazz;
  }

  @Override
  public Collection<MutationDetails> intercept(
      Collection<MutationDetails> mutations, Mutater m) {
    return FCollection.filter(mutations, isEquivalent(m).negate());
  }

  private Predicate<MutationDetails> isEquivalent(Mutater m) {
    return a -> {
      if (!MUTATOR_IDS.contains(a.getMutator())) {
        return false;
      }
      final MethodTree method = PrimitiveEquivalentFilter.this.currentClass.methods().stream()
          .filter(MethodMatchers.forLocation(a.getId().getLocation()))
          .findFirst()
          .get();
      return ZERO_CONSTANTS.contains(method.realInstructionBefore(a.getInstructionIndex()).getOpcode());
    };
  }

  @Override
  public void end() {
    this.currentClass = null;
  }

}

class EmptyReturnsFilter implements MutationInterceptor {

    private static final Slot<AbstractInsnNode> MUTATED_INSTRUCTION = Slot.create(AbstractInsnNode.class);
    
    private static final SequenceMatcher<AbstractInsnNode> CONSTANT_ZERO = QueryStart
        .any(AbstractInsnNode.class)
        .zeroOrMore(QueryStart.match(anyInstruction()))
        .then(isZeroConstant())
        .then(methodCallNamed("valueOf"))
        .then(isInstruction(MUTATED_INSTRUCTION.read()))
        .zeroOrMore(QueryStart.match(anyInstruction()))
        .compile(QueryParams.params(AbstractInsnNode.class)
            .withIgnores(notAnInstruction())
            );
    
  private static final Set<String> MUTATOR_IDS = new HashSet<>();
  private static final Set<Integer> ZERO_CONSTANTS = new HashSet<>();
  static {
    ZERO_CONSTANTS.add(Opcodes.ICONST_0);
    ZERO_CONSTANTS.add(Opcodes.LCONST_0);
    ZERO_CONSTANTS.add(Opcodes.FCONST_0);
    ZERO_CONSTANTS.add(Opcodes.DCONST_0);

    MUTATOR_IDS.add(EmptyObjectReturnValsMutator.EMPTY_RETURN_VALUES.getGloballyUniqueId());
    MUTATOR_IDS.add(BooleanFalseReturnValsMutator.BOOLEAN_FALSE_RETURN.getGloballyUniqueId());
  }

  private ClassTree currentClass;

  @Override
  public InterceptorType type() {
    return InterceptorType.FILTER;
  }

@Override
  public void begin(ClassTree clazz) {
    this.currentClass = clazz;
  }

  @Override
  public Collection<MutationDetails> intercept(
      Collection<MutationDetails> mutations, Mutater m) {
    return FCollection.filter(mutations, isEquivalent(m).negate());
  }

  private static Match<AbstractInsnNode> isZeroConstant() {
      return (context,node) -> ZERO_CONSTANTS.contains(node.getOpcode());
  }
  
  private Predicate<MutationDetails> isEquivalent(Mutater m) {
    return new Predicate<MutationDetails>() {
      @Override
      public boolean test(MutationDetails a) {
        if (!MUTATOR_IDS.contains(a.getMutator())) {
          return false;
        }

        final MethodTree method = EmptyReturnsFilter.this.currentClass.methods().stream()
            .filter(MethodMatchers.forLocation(a.getId().getLocation()))
            .findFirst()
            .get();
        final int mutatedInstruction = a.getInstructionIndex();
        return returnsZeroValue(method, mutatedInstruction)
            || returnsEmptyString(method, mutatedInstruction)
            || returns(method, mutatedInstruction, "java/util/Optional","empty")
            || returns(method, mutatedInstruction, "java/util/Collections","emptyList")
            || returns(method, mutatedInstruction, "java/util/Collections","emptySet")
            || returns(method, mutatedInstruction, "java/util/List","of")
            || returns(method, mutatedInstruction, "java/util/Set","of");
      }

      private Boolean returnsZeroValue(MethodTree method,
          int mutatedInstruction) {
          final Context<AbstractInsnNode> context = Context.start(method.instructions(), false);
          context.store(MUTATED_INSTRUCTION.write(), method.instruction(mutatedInstruction));
          return CONSTANT_ZERO.matches(method.instructions(), context);              
      }
          
      private boolean returns(MethodTree method, int mutatedInstruction, String owner, String name) {
        final AbstractInsnNode node = method.realInstructionBefore(mutatedInstruction);
        if (node instanceof MethodInsnNode ) {
          final MethodInsnNode call = (MethodInsnNode) node;
          return call.owner.equals(owner) && call.name.equals(name) && takesNoArguments(call.desc);
        }
        return false;
      }

      private boolean takesNoArguments(String desc) {
        return Type.getArgumentTypes(desc).length == 0;
      }

      private boolean returnsEmptyString(MethodTree method,
          int mutatedInstruction) {
        final AbstractInsnNode node = method.realInstructionBefore(mutatedInstruction);
        if (node instanceof LdcInsnNode ) {
          final LdcInsnNode ldc = (LdcInsnNode) node;
          return "".equals(ldc.cst);
        }
        return false;
      }

    };
  }

  @Override
  public void end() {
    this.currentClass = null;
  }

}

class NullReturnsFilter implements MutationInterceptor {

  private static final String MUTATOR_ID = NullReturnValsMutator.NULL_RETURN_VALUES.getGloballyUniqueId();

  private ClassTree currentClass;

  @Override
  public InterceptorType type() {
    return InterceptorType.FILTER;
  }

  @Override
  public void begin(ClassTree clazz) {
    this.currentClass = clazz;
  }

  @Override
  public Collection<MutationDetails> intercept(
      Collection<MutationDetails> mutations, Mutater m) {
    return FCollection.filter(mutations, isEquivalent(m).negate());
  }

  private Predicate<MutationDetails> isEquivalent(Mutater m) {
    return new Predicate<MutationDetails>() {
      @Override
      public boolean test(MutationDetails a) {
        if (!MUTATOR_ID.equals(a.getMutator())) {
          return false;
        }

        final MethodTree method = NullReturnsFilter.this.currentClass.methods().stream()
            .filter(MethodMatchers.forLocation(a.getId().getLocation()))
            .findFirst()
            .get();
        final int mutatedInstruction = a.getInstructionIndex();
        return returnsNull(method, mutatedInstruction);
      }

      private Boolean returnsNull(MethodTree method,
          int mutatedInstruction) {
        return method.realInstructionBefore(mutatedInstruction).getOpcode() == Opcodes.ACONST_NULL;
      }
    };
  }

  @Override
  public void end() {
    this.currentClass = null;
  }

}
