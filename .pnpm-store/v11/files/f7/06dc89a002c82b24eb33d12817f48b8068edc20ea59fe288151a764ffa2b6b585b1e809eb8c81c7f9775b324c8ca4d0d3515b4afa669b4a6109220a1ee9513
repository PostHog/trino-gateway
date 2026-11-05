"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.default = void 0;
var _react = _interopRequireWildcard(require("react"));
var _propTypes = _interopRequireDefault(require("prop-types"));
var _classnames = _interopRequireDefault(require("classnames"));
var _constants = require("@douyinfe/semi-foundation/lib/cjs/tag/constants");
require("@douyinfe/semi-foundation/lib/cjs/tag/tag.css");
var _baseComponent = _interopRequireDefault(require("../_base/baseComponent"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
const prefixCls = _constants.cssClasses.PREFIX;
/**
 * SplitTagGroup wraps a list of `Tag` siblings and renders them as a single
 * connected group: the first child gets rounded corners on the leading edge,
 * the last child on the trailing edge, and inner children get zero radius.
 *
 * The class injection happens at render time via `React.Children.map` +
 * `cloneElement`, so we never reach into the DOM and can react to children
 * changes synchronously without a `MutationObserver`.
 */
class SplitTagGroup extends _baseComponent.default {
  constructor() {
    super(...arguments);
    /**
     * Walk only the *direct* children, skipping non-elements (text / nullish),
     * and inject `semi-tag-first` / `semi-tag-last` based on element index
     * within the visible-element list. Existing className on the child is
     * preserved.
     */
    this.decorateChildren = children => {
      const validChildren = _react.Children.toArray(children).filter(child => /*#__PURE__*/(0, _react.isValidElement)(child));
      const lastIndex = validChildren.length - 1;
      let visibleIndex = -1;
      return _react.Children.map(children, child => {
        var _a;
        if (! /*#__PURE__*/(0, _react.isValidElement)(child)) {
          return child;
        }
        visibleIndex += 1;
        const extraCls = (0, _classnames.default)((_a = child.props) === null || _a === void 0 ? void 0 : _a.className, {
          [`${prefixCls}-first`]: visibleIndex === 0,
          [`${prefixCls}-last`]: visibleIndex === lastIndex
        });
        return /*#__PURE__*/(0, _react.cloneElement)(child, {
          className: extraCls
        });
      });
    };
  }
  render() {
    const {
      children,
      style,
      className
    } = this.props;
    const cls = (0, _classnames.default)(`${prefixCls}-split`, className);
    return /*#__PURE__*/_react.default.createElement("div", {
      className: cls,
      style: style,
      role: "group",
      "aria-label": this.props['aria-label']
    }, this.decorateChildren(children));
  }
}
exports.default = SplitTagGroup;
SplitTagGroup.propTypes = {
  style: _propTypes.default.object,
  className: _propTypes.default.string,
  children: _propTypes.default.node,
  'aria-label': _propTypes.default.string
};
SplitTagGroup.defaultProps = {};