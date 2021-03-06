package org.antlr.intellij.plugin.preview;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseEventArea;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.event.EditorMouseMotionListener;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.PopupChooserBuilder;
import com.intellij.ui.components.JBList;
import org.antlr.intellij.plugin.ANTLRv4PluginController;
import org.antlr.intellij.plugin.actions.MyActionUtils;
import org.antlr.v4.runtime.atn.AmbiguityInfo;

import javax.swing.*;
import java.awt.event.MouseEvent;
import java.util.List;

class PreviewEditorMouseListener implements EditorMouseListener, EditorMouseMotionListener {
	private InputPanel inputPanel;

	public PreviewEditorMouseListener(InputPanel inputPanel) {
		this.inputPanel = inputPanel;
	}

	@Override
	public void mouseExited(EditorMouseEvent e) {
		InputPanel.removeTokenInfoHighlighters(e.getEditor());
	}

	@Override
	public void mouseClicked(EditorMouseEvent e) {
		final int offset = getEditorCharOffset(e);
		if ( offset<0 ) return;

		final Editor editor=e.getEditor();
		ANTLRv4PluginController controller = ANTLRv4PluginController.getInstance(editor.getProject());
		final PreviewState previewState = controller.getPreviewState();
		if ( previewState==null ) {
			return;
		}

		if ( e.getMouseEvent().getButton()==MouseEvent.BUTTON3 ) { // right click
			rightClick(previewState, editor, offset);
			return;
		}

		MouseEvent mouseEvent=e.getMouseEvent();
		if ( mouseEvent.isControlDown() ) {
			inputPanel.setCursorToGrammarElement(e.getEditor().getProject(), previewState, offset);
		}
		else if ( mouseEvent.isAltDown() ) {
			inputPanel.setCursorToGrammarRule(e.getEditor().getProject(), previewState, offset);
		}
		editor.getMarkupModel().removeAllHighlighters();
	}

	public void rightClick(final PreviewState previewState, Editor editor, int offset) {
		if (previewState.parsingResult == null) return;
		final List<RangeHighlighter> highlightersAtOffset = MyActionUtils.getRangeHighlightersAtOffset(editor, offset);
		if (highlightersAtOffset.size() == 0) {
			return;
		}

		final AmbiguityInfo ambigEvent =
			(AmbiguityInfo)MyActionUtils.getHighlighterWithDecisionEventType(highlightersAtOffset,
																			 AmbiguityInfo.class);
		if ( ambigEvent==null ) {
			return;
		}

		// Show popup menu to choose "show trees"
		final JBList list = new JBList("Show all phrase interpretations");
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		JBPopupFactory factory = JBPopupFactory.getInstance();
		PopupChooserBuilder builder = factory.createListPopupBuilder(list);
		builder.setItemChoosenCallback(
			new Runnable() {
				@Override
				public void run() {
					// pop up subtrees for ambig intrepretation
					ShowAmbigTreesDialog dialog = new ShowAmbigTreesDialog();
					dialog.setTrees(previewState, ambigEvent);
					dialog.pack();
					dialog.setVisible(true);
				}
			}
		);
		JBPopup popup = builder.createPopup();
		popup.showInBestPositionFor(editor);
	}

	@Override
	public void mouseMoved(EditorMouseEvent e){
		int offset = getEditorCharOffset(e);
		if ( offset<0 ) return;

		Editor editor=e.getEditor();
		ANTLRv4PluginController controller = ANTLRv4PluginController.getInstance(editor.getProject());
		PreviewState previewState =	controller.getPreviewState();
		if ( previewState==null ) {
			return;
		}

		MouseEvent mouseEvent=e.getMouseEvent();
		InputPanel.removeTokenInfoHighlighters(editor);
		if ( mouseEvent.isControlDown() && previewState.parsingResult!=null ) {
			inputPanel.showTokenInfoUponMeta(editor, previewState, offset);
		}
		else if ( mouseEvent.isAltDown() && previewState.parsingResult!=null ) {
			inputPanel.showParseRegion(e, editor, previewState, offset);
		}
		else { // just moving around, show any errors or hints
			InputPanel.showTooltips(e, editor, previewState, offset);
		}
	}

	public int getEditorCharOffset(EditorMouseEvent e) {
		if ( e.getArea()!=EditorMouseEventArea.EDITING_AREA ) {
			return -1;
		}

		MouseEvent mouseEvent=e.getMouseEvent();
		Editor editor=e.getEditor();
		int offset = MyActionUtils.getMouseOffset(mouseEvent, editor);
//		System.out.println("offset="+offset);

		if ( offset >= editor.getDocument().getTextLength() ) {
			return -1;
		}

		// Mouse has moved so make sure we don't show any token information tooltips
		InputPanel.removeTokenInfoHighlighters(editor);
		return offset;
	}

	// ------------------------

	@Override
	public void mousePressed(EditorMouseEvent e) {
	}

	@Override
	public void mouseReleased(EditorMouseEvent e) {
	}

	@Override
	public void mouseEntered(EditorMouseEvent e) {
	}

	@Override
	public void mouseDragged(EditorMouseEvent e) {
	}
}
