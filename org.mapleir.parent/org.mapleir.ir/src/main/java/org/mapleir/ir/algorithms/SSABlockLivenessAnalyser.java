package org.mapleir.ir.algorithms;

import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Map;
import java.util.Queue;

import org.mapleir.flowgraph.edges.FlowEdge;
import org.mapleir.flowgraph.edges.FlowEdges;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.Opcode;
import org.mapleir.ir.code.Stmt;
import org.mapleir.ir.code.expr.PhiExpr;
import org.mapleir.ir.code.expr.VarExpr;
import org.mapleir.ir.code.stmt.copy.CopyPhiStmt;
import org.mapleir.ir.code.stmt.copy.CopyVarStmt;
import org.mapleir.ir.locals.Local;
import org.mapleir.ir.locals.LocalsPool;
import org.mapleir.stdlib.collections.bitset.GenericBitSet;
import org.mapleir.stdlib.collections.map.NullPermeableHashMap;

/**
 * A simple fixed-point, worklist-based liveness analyser that supports both SSA and non-SSA flow graphs.
 * If your flow graph is already in SSA, you should use {@link DominanceLivenessAnalyser} instead.
 */
public class SSABlockLivenessAnalyser implements Liveness<BasicBlock> {
	private final NullPermeableHashMap<BasicBlock, GenericBitSet<Local>> use;
	private final NullPermeableHashMap<BasicBlock, GenericBitSet<Local>> def;

	// phi uses and phi defs must be handled specially because there are special semantics about the dataflow.
	private final NullPermeableHashMap<BasicBlock, NullPermeableHashMap<BasicBlock, GenericBitSet<Local>>> phiUse;
	private final NullPermeableHashMap<BasicBlock, GenericBitSet<Local>> phiDef;

	private final NullPermeableHashMap<BasicBlock, GenericBitSet<Local>> out;
	private final NullPermeableHashMap<BasicBlock, GenericBitSet<Local>> in;

	private final Queue<BasicBlock> queue;
	private final LocalsPool locals;

	private final ControlFlowGraph cfg;

	public SSABlockLivenessAnalyser(ControlFlowGraph cfg) {
		locals = cfg.getLocals();
		use = new NullPermeableHashMap<>(locals);
		def = new NullPermeableHashMap<>(locals);
		phiUse = new NullPermeableHashMap<>(() -> new NullPermeableHashMap<>(locals));
		phiDef = new NullPermeableHashMap<>(locals);

		out = new NullPermeableHashMap<>(locals);
		in = new NullPermeableHashMap<>(locals);

		queue = new LinkedList<>();

		this.cfg = cfg;

		init();
	}

	private void enqueue(BasicBlock b) {
		if (!queue.contains(b)) {
			// System.out.println("Enqueue " + b);
			queue.add(b);
		}
	}

	private void init() {
		// initialize in and out
		for (BasicBlock b : cfg.vertices()) {
			in.getNonNull(b);
			out.getNonNull(b);
		}

		// compute def, use, and phi for each block
		for (BasicBlock b : cfg.vertices())
			precomputeBlock(b);

		// enqueue every block
		for (BasicBlock b : cfg.vertices())
			enqueue(b);

		// System.out.println();
		// System.out.println();
		// for (BasicBlock b : cfg.vertices())
		// System.out.println(b.getId() + " |||| DEF: " + def.get(b) + " ||||| USE: " + use.get(b));
		// System.out.println();
		// for (BasicBlock b : cfg.vertices())
		// System.out.println(b.getId() + " |||| \u0278DEF: " + phiDef.get(b) + " ||||| \u0278USE: " + phiUse.get(b));
	}

	// compute def, use, and phi for given block
	private void precomputeBlock(BasicBlock b) {
		def.getNonNull(b);
		use.getNonNull(b);
		phiUse.getNonNull(b);
		phiDef.getNonNull(b);

		// we have to iterate in reverse order because a definition will kill a use in the current block
		// this is so that uses do not escape a block if its def is in the same block. this is basically
		// simulating a statement graph analysis
		ListIterator<Stmt> it = b.listIterator(b.size());
		while (it.hasPrevious()) {
			Stmt stmt = it.previous();
			int opcode = stmt.getOpcode();
			if (opcode == Opcode.PHI_STORE) {
				CopyPhiStmt copy = (CopyPhiStmt) stmt;
				phiDef.get(b).add(copy.getVariable().getLocal());
				PhiExpr phi = copy.getExpression();
				for (Map.Entry<BasicBlock, Expr> e : phi.getArguments().entrySet()) {
					BasicBlock exprSource = e.getKey();
					Expr phiExpr = e.getValue();
					GenericBitSet<Local> useSet = phiUse.get(b).getNonNull(exprSource);
                    for (Expr child : phiExpr.enumerateWithSelf()) {
                        if (child.getOpcode() == Opcode.LOCAL_LOAD) {
                            useSet.add(((VarExpr) child).getLocal());
                        }
                    }
				}
			} else {
				if (opcode == Opcode.LOCAL_STORE) {
					CopyVarStmt copy = (CopyVarStmt) stmt;
					Local l = copy.getVariable().getLocal();
					def.get(b).add(l);
					use.get(b).remove(l);
					// 2/17/19: we now no longer need treat handler edges differently, as
					// NaturalisationPass should eliminate all natural flow into handlers.
				}
				for (Expr c : stmt.enumerateOnlyChildren()) {
					if (c.getOpcode() == Opcode.LOCAL_LOAD) {
						VarExpr v = (VarExpr) c;
						use.get(b).add(v.getLocal());
					}
				}
			}
		}
	}

	@Override
	public GenericBitSet<Local> in(BasicBlock b) {
		return in.get(b);
	}

	@Override
	public GenericBitSet<Local> out(BasicBlock b) {
		return out.get(b);
	}

	public void compute() {
		// +use and -def affect out
		// -use and +def affect in
		// negative handling always goes after positive and any adds
		while (!queue.isEmpty()) {
			BasicBlock b = queue.remove();
			// System.out.println("\n\nProcessing " + b.getId());

			GenericBitSet<Local> oldIn = new GenericBitSet<>(in.get(b));
			GenericBitSet<Local> curIn = new GenericBitSet<>(use.get(b));
			GenericBitSet<Local> curOut = locals.createBitSet();

			GenericBitSet<Local> exceptionalOut = locals.createBitSet();
			// Account for phi definitions/uses per edge, before unioning successors.
			for (FlowEdge<BasicBlock> succEdge : cfg.getEdges(b)) {
				BasicBlock successor = succEdge.dst();
				GenericBitSet<Local> edgeLive = in.get(successor).relativeComplement(phiDef.get(successor));
				edgeLive.addAll(phiUse.get(successor).getNonNull(b));
				curOut.addAll(edgeLive);
				if (succEdge.getType() == FlowEdges.TRYCATCH) {
					exceptionalOut.addAll(edgeLive);
				}
			}

			// negative phi handling for uses
			for (FlowEdge<BasicBlock> predEdge : cfg.getReverseEdges(b))
				curIn.removeAll(phiUse.get(b).getNonNull(predEdge.src()).relativeComplement(use.get(b)));

			// positive phi handling for defs
			curIn.addAll(phiDef.get(b));
			oldIn.addAll(phiDef.get(b));

			// in[n] = use[n] U(out[n] - def[n])
			curIn.addAll(curOut.relativeComplement(def.get(b)));

			// A handler can observe the value from BEFORE a throwing assignment.
			// In particular, x = invoke() does not define x when invoke throws.
			// Ordinary block defs therefore cannot kill exceptional live-ins. This
			// analysis runs before SSAGenPass splits handler-live redefinitions;
			// treating exceptional edges as ordinary end-of-block edges here used
			// to delete initial null values in nested catch/finally paths.
			curIn.addAll(exceptionalOut);

			in.put(b, curIn);
			out.put(b, curOut);

			// queue preds if dataflow state changed
			if (!oldIn.equals(curIn)) {
				cfg.getReverseEdges(b).stream().map(e -> e.src()).forEach(this::enqueue);

				// for (BasicBlock b2 : cfg.vertices())
				// System.out.println(b2.getId() + " |||| IN: " + in.get(b2) + " ||||| OUT: " + out.get(b2));
			}
		}
	}

	public ControlFlowGraph getGraph() {
		return cfg;
	}
}
