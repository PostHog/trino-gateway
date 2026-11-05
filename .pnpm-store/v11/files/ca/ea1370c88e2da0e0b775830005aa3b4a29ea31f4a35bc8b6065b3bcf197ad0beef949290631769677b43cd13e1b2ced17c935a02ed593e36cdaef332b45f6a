"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.default = void 0;
var _get2 = _interopRequireDefault(require("lodash/get"));
var _react = _interopRequireWildcard(require("react"));
var _isNullOrUndefined = _interopRequireDefault(require("@douyinfe/semi-foundation/lib/cjs/utils/isNullOrUndefined"));
var _uuid = require("@douyinfe/semi-foundation/lib/cjs/utils/uuid");
var _semiGlobal = _interopRequireDefault(require("../_utils/semi-global"));
var _classnames = _interopRequireDefault(require("classnames"));
function _getRequireWildcardCache(e) { if ("function" != typeof WeakMap) return null; var r = new WeakMap(), t = new WeakMap(); return (_getRequireWildcardCache = function (e) { return e ? t : r; })(e); }
function _interopRequireWildcard(e, r) { if (!r && e && e.__esModule) return e; if (null === e || "object" != typeof e && "function" != typeof e) return { default: e }; var t = _getRequireWildcardCache(r); if (t && t.has(e)) return t.get(e); var n = { __proto__: null }, a = Object.defineProperty && Object.getOwnPropertyDescriptor; for (var u in e) if ("default" !== u && {}.hasOwnProperty.call(e, u)) { var i = a ? Object.getOwnPropertyDescriptor(e, u) : null; i && (i.get || i.set) ? Object.defineProperty(n, u, i) : n[u] = e[u]; } return n.default = e, t && t.set(e, n), n; }
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
var __rest = void 0 && (void 0).__rest || function (s, e) {
  var t = {};
  for (var p in s) if (Object.prototype.hasOwnProperty.call(s, p) && e.indexOf(p) < 0) t[p] = s[p];
  if (s != null && typeof Object.getOwnPropertySymbols === "function") for (var i = 0, p = Object.getOwnPropertySymbols(s); i < p.length; i++) {
    if (e.indexOf(p[i]) < 0 && Object.prototype.propertyIsEnumerable.call(s, p[i])) t[p[i]] = s[p[i]];
  }
  return t;
};
function Icon() {
  let props = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : {};
  const {
      id: propsId,
      className,
      customIconCls
    } = props,
    rest = __rest(props, ["id", "className", "customIconCls"]);
  const globalIndicator = (0, _get2.default)(_semiGlobal.default, 'config.overrideDefaultProps.Spin.indicator');
  if (globalIndicator && /*#__PURE__*/_react.default.isValidElement(globalIndicator)) {
    return /*#__PURE__*/_react.default.cloneElement(globalIndicator, {
      className: (0, _classnames.default)({
        [customIconCls]: customIconCls,
        [className]: className
      })
    });
  }
  /**
   * NOTE(SSR / Next.js):
   * We must keep the original SVG implementation based on <linearGradient> + url(#id).
   * However, generating ids via module-level counters or random values during render can
   * cause hydration mismatch (server/client ids differ).
   *
   * Strategy:
   * - On SSR and the client's initial render, use a stable fallback id to keep markup identical.
   * - After mount (client-only), replace it with an instance-unique id to avoid cross-instance
   *   collisions where one Spin could affect another via duplicate ids.
   *
   * If consumers pass `props.id`, we treat it as a stable explicit seed.
  */
  const fallbackId = 'linearGradient-semi-spin';
  const [gradientId, setGradientId] = (0, _react.useState)(() => {
    if (!(0, _isNullOrUndefined.default)(propsId)) {
      return `linearGradient-${propsId}`;
    }
    return fallbackId;
  });
  (0, _react.useEffect)(() => {
    if (!(0, _isNullOrUndefined.default)(propsId)) {
      // Explicit id is stable; no need to mutate after mount.
      setGradientId(`linearGradient-${propsId}`);
      return;
    }
    const unique = (0, _uuid.getUuidShort)({
      prefix: 'semi-spin-gradient'
    });
    setGradientId(`linearGradient-${unique}`);
  }, [propsId]);
  return /*#__PURE__*/_react.default.createElement("svg", Object.assign({}, rest, {
    className: className,
    width: "48",
    height: "48",
    viewBox: "0 0 36 36",
    version: "1.1",
    xmlns: "http://www.w3.org/2000/svg",
    "aria-hidden": true,
    "data-icon": "spin"
  }), /*#__PURE__*/_react.default.createElement("defs", null, /*#__PURE__*/_react.default.createElement("linearGradient", {
    x1: "0%",
    y1: "100%",
    x2: "100%",
    y2: "100%",
    id: gradientId
  }, /*#__PURE__*/_react.default.createElement("stop", {
    stopColor: "currentColor",
    stopOpacity: "0",
    offset: "0%"
  }), /*#__PURE__*/_react.default.createElement("stop", {
    stopColor: "currentColor",
    stopOpacity: "0.50",
    offset: "39.9430698%"
  }), /*#__PURE__*/_react.default.createElement("stop", {
    stopColor: "currentColor",
    offset: "100%"
  }))), /*#__PURE__*/_react.default.createElement("g", {
    stroke: "none",
    strokeWidth: "1",
    fill: "none",
    fillRule: "evenodd"
  }, /*#__PURE__*/_react.default.createElement("rect", {
    fillOpacity: "0.01",
    fill: "none",
    x: "0",
    y: "0",
    width: "36",
    height: "36"
  }), /*#__PURE__*/_react.default.createElement("path", {
    d: "M34,18 C34,9.163444 26.836556,2 18,2 C11.6597233,2 6.18078805,5.68784135 3.59122325,11.0354951",
    stroke: `url(#${gradientId})`,
    strokeWidth: "4",
    strokeLinecap: "round"
  })));
}
var _default = exports.default = Icon;