import { NodeViewWrapper, NodeViewContent } from '@tiptap/react';
import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { strings } from '@douyinfe/semi-foundation/lib/es/aiChatInput/constants';
const InputSlotComponent = props => {
  const {
    editor,
    node,
    getPos
  } = props;
  const isEmpty = node.textContent === strings.ZERO_WIDTH_CHAR;
  const placeholder = node.attrs.placeholder || '';
  /**
   * IMPORTANT:
   * inputSlot's NodeViewRenderer.update() is intentionally skipped during IME composition
   * (see extension/inputSlot/index.tsx). That means React won't re-render when
   * editor.view.composing changes.
   *
   * To avoid placeholder covering the live IME text, we listen to DOM
   * composition events and drive a local state update.
   */
  const [hidePlaceholderInComposition, setHidePlaceholderInComposition] = useState(false);
  const isSelectionInsideThisSlot = useCallback(() => {
    if (!editor || typeof getPos !== 'function') {
      return false;
    }
    const pos = getPos();
    const {
      from,
      to
    } = editor.state.selection;
    // getPos() returns the position before this node.
    // Selection inside the node should be within (pos, pos + node.nodeSize).
    return from > pos && to < pos + node.nodeSize;
  }, [editor, getPos, node.nodeSize]);
  useEffect(() => {
    var _a;
    if (!((_a = editor === null || editor === void 0 ? void 0 : editor.view) === null || _a === void 0 ? void 0 : _a.dom)) {
      return undefined;
    }
    const onCompositionStart = () => {
      if (isSelectionInsideThisSlot()) {
        setHidePlaceholderInComposition(true);
      }
    };
    const onCompositionEnd = () => {
      setHidePlaceholderInComposition(false);
    };
    // Use capture to ensure we catch events even when ProseMirror stops propagation.
    const dom = editor.view.dom;
    dom.addEventListener('compositionstart', onCompositionStart, true);
    dom.addEventListener('compositionend', onCompositionEnd, true);
    dom.addEventListener('compositioncancel', onCompositionEnd, true);
    return () => {
      dom.removeEventListener('compositionstart', onCompositionStart, true);
      dom.removeEventListener('compositionend', onCompositionEnd, true);
      dom.removeEventListener('compositioncancel', onCompositionEnd, true);
    };
  }, [editor, isSelectionInsideThisSlot]);
  // Hide placeholder when composing to avoid covering IME input.
  const shouldShowPlaceholder = isEmpty && !hidePlaceholderInComposition;
  const placeholderRef = useRef(null);
  const [placeholderWidth, setPlaceholderWidth] = useState(undefined);
  useLayoutEffect(() => {
    if (shouldShowPlaceholder && placeholderRef.current) {
      const timer = setTimeout(() => {
        var _a;
        setPlaceholderWidth((_a = placeholderRef.current) === null || _a === void 0 ? void 0 : _a.offsetWidth);
      });
      return () => {
        clearTimeout(timer);
      };
    }
    return undefined;
  }, [shouldShowPlaceholder, placeholder]);
  return /*#__PURE__*/React.createElement(NodeViewWrapper, {
    as: "span",
    className: "input-slot",
    style: {
      minWidth: shouldShowPlaceholder && placeholderWidth ? `${placeholderWidth}px` : undefined
    }
  }, /*#__PURE__*/React.createElement("span", {
    ref: placeholderRef,
    "data-placeholder": true,
    contentEditable: false,
    className: "input-slot-placeholder",
    style: {
      display: shouldShowPlaceholder ? undefined : 'none'
    }
  }, placeholder), /*#__PURE__*/React.createElement(NodeViewContent, {
    as: "span",
    className: "content"
  }));
};
export default InputSlotComponent;