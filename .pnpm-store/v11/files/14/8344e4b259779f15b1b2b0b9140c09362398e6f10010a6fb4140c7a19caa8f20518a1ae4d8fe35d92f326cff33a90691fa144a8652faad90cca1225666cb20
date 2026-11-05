"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.default = void 0;
var _react = require("@tiptap/react");
var _react2 = _interopRequireWildcard(require("react"));
var _constants = require("@douyinfe/semi-foundation/lib/cjs/aiChatInput/constants");
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
const InputSlotComponent = props => {
  const {
    editor,
    node,
    getPos
  } = props;
  const isEmpty = node.textContent === _constants.strings.ZERO_WIDTH_CHAR;
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
  const [hidePlaceholderInComposition, setHidePlaceholderInComposition] = (0, _react2.useState)(false);
  const isSelectionInsideThisSlot = (0, _react2.useCallback)(() => {
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
  (0, _react2.useEffect)(() => {
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
  const placeholderRef = (0, _react2.useRef)(null);
  const [placeholderWidth, setPlaceholderWidth] = (0, _react2.useState)(undefined);
  (0, _react2.useLayoutEffect)(() => {
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
  return /*#__PURE__*/_react2.default.createElement(_react.NodeViewWrapper, {
    as: "span",
    className: "input-slot",
    style: {
      minWidth: shouldShowPlaceholder && placeholderWidth ? `${placeholderWidth}px` : undefined
    }
  }, /*#__PURE__*/_react2.default.createElement("span", {
    ref: placeholderRef,
    "data-placeholder": true,
    contentEditable: false,
    className: "input-slot-placeholder",
    style: {
      display: shouldShowPlaceholder ? undefined : 'none'
    }
  }, placeholder), /*#__PURE__*/_react2.default.createElement(_react.NodeViewContent, {
    as: "span",
    className: "content"
  }));
};
var _default = exports.default = InputSlotComponent;