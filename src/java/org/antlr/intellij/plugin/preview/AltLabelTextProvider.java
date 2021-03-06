package org.antlr.intellij.plugin.preview;

import org.antlr.intellij.plugin.parsing.PreviewInterpreterRuleContext;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.atn.DecisionState;
import org.antlr.v4.runtime.misc.Pair;
import org.antlr.v4.runtime.tree.Tree;
import org.antlr.v4.runtime.tree.Trees;
import org.antlr.v4.runtime.tree.gui.TreeTextProvider;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ast.AltAST;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AltLabelTextProvider implements TreeTextProvider {
	protected final Parser parser;
	protected final Grammar g;
	protected final Map<DecisionState, String[]> stateToAltLabels = new HashMap<>();

	public AltLabelTextProvider(Parser parser, Grammar g) {
		this.parser = parser;
		this.g = g;
	}

	public String[] getAltLabels(Rule r) {
		String[] altLabels = null;
		Map<String, List<Pair<Integer, AltAST>>> altLabelsMap = r.getAltLabels();
		if ( altLabelsMap!=null ) {
			altLabels = new String[r.getOriginalNumberOfAlts() + 1];
			for (String altLabel : altLabelsMap.keySet()) {
				List<Pair<Integer, AltAST>> pairs = altLabelsMap.get(altLabel);
				for (Pair<Integer, AltAST> pair : pairs) {
					altLabels[pair.a] = altLabel;
				}
			}
		}
		return altLabels;
	}

	@Override
	public String getText(Tree node) {
		if ( node instanceof PreviewInterpreterRuleContext) {
			PreviewInterpreterRuleContext uberNode = (PreviewInterpreterRuleContext)node;
			Rule r = g.getRule(uberNode.getRuleIndex());
			String[] altLabels = getAltLabels(r);
			if ( altLabels!=null ) {
				return r.name+":"+altLabels[uberNode.getOuterAltNum()];
			}
			else {
				return r.name + ":" + uberNode.getOuterAltNum();
			}
		}
		return Trees.getNodeText(node, Arrays.asList(parser.getRuleNames()));
	}
}
