"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.default = void 0;
var _isEqual2 = _interopRequireDefault(require("lodash/isEqual"));
var _react = _interopRequireDefault(require("react"));
var _classnames = _interopRequireDefault(require("classnames"));
var _foundation = _interopRequireDefault(require("@douyinfe/semi-foundation/lib/cjs/jsonViewer/foundation"));
require("@douyinfe/semi-foundation/lib/cjs/jsonViewer/jsonViewer.css");
var _constants = require("@douyinfe/semi-foundation/lib/cjs/jsonViewer/constants");
var _buttonGroup = _interopRequireDefault(require("../button/buttonGroup"));
var _button = _interopRequireDefault(require("../button"));
var _input = _interopRequireDefault(require("../input"));
var _dragMove = _interopRequireDefault(require("../dragMove"));
var _semiIcons = require("@douyinfe/semi-icons");
var _baseComponent = _interopRequireDefault(require("../_base/baseComponent"));
var _reactDom = require("react-dom");
var _localeConsumer = _interopRequireDefault(require("../locale/localeConsumer"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
var __rest = void 0 && (void 0).__rest || function (s, e) {
  var t = {};
  for (var p in s) if (Object.prototype.hasOwnProperty.call(s, p) && e.indexOf(p) < 0) t[p] = s[p];
  if (s != null && typeof Object.getOwnPropertySymbols === "function") for (var i = 0, p = Object.getOwnPropertySymbols(s); i < p.length; i++) {
    if (e.indexOf(p[i]) < 0 && Object.prototype.propertyIsEnumerable.call(s, p[i])) t[p[i]] = s[p[i]];
  }
  return t;
};
const prefixCls = _constants.cssClasses.PREFIX;
class JsonViewerCom extends _baseComponent.default {
  constructor(props) {
    super(props);
    this.isComposing = false;
    this.resizeObserver = null;
    this.resizeRafId = null;
    this.lastObservedWidth = null;
    this.searchHandler = () => {
      var _a;
      const value = (_a = this.searchInputRef.current) === null || _a === void 0 ? void 0 : _a.value;
      this.foundation.search(value);
    };
    this.changeSearchOptions = key => {
      this.foundation.setSearchOptions(key);
    };
    this.editorRef = /*#__PURE__*/_react.default.createRef();
    this.searchInputRef = /*#__PURE__*/_react.default.createRef();
    this.replaceInputRef = /*#__PURE__*/_react.default.createRef();
    this.foundation = new _foundation.default(this.adapter);
    this.state = {
      searchOptions: {
        caseSensitive: false,
        wholeWord: false,
        regex: false
      },
      showSearchBar: false,
      customRenderMap: new Map()
    };
  }
  componentDidMount() {
    this.foundation.init();
    this.setupResizeObserver();
  }
  teardownResizeObserver() {
    if (this.resizeObserver) {
      this.resizeObserver.disconnect();
      this.resizeObserver = null;
    }
    if (this.resizeRafId !== null) {
      cancelAnimationFrame(this.resizeRafId);
      this.resizeRafId = null;
    }
    this.lastObservedWidth = null;
  }
  setupResizeObserver() {
    var _a;
    // Only needed for autoWrap, since line wraps depend on container width.
    if (!((_a = this.props.options) === null || _a === void 0 ? void 0 : _a.autoWrap)) {
      this.teardownResizeObserver();
      return;
    }
    const el = this.editorRef.current;
    if (!el || typeof ResizeObserver === 'undefined') {
      return;
    }
    // Avoid duplicated observers when re-init.
    this.teardownResizeObserver();
    this.lastObservedWidth = el.getBoundingClientRect().width;
    this.resizeObserver = new ResizeObserver(entries => {
      var _a;
      const entry = entries && entries[0];
      if (!entry) {
        return;
      }
      const nextWidth = (_a = entry.contentRect) === null || _a === void 0 ? void 0 : _a.width;
      if (typeof nextWidth !== 'number') {
        return;
      }
      // Only react to width changes, which affect wrapping.
      if (this.lastObservedWidth !== null && Math.abs(nextWidth - this.lastObservedWidth) < 0.5) {
        return;
      }
      this.lastObservedWidth = nextWidth;
      // Coalesce multiple resize events.
      if (this.resizeRafId !== null) {
        cancelAnimationFrame(this.resizeRafId);
      }
      this.resizeRafId = requestAnimationFrame(() => {
        var _a;
        this.resizeRafId = null;
        // Clear measured heights cache when container size changes.
        // NOTE: _view and _measuredHeights are internal implementation details.
        const jsonViewer = this.foundation.jsonViewer;
        if (jsonViewer && jsonViewer._view && jsonViewer._view._measuredHeights) {
          jsonViewer._view._measuredHeights = {};
        }
        (_a = this.foundation.jsonViewer) === null || _a === void 0 ? void 0 : _a.layout();
      });
    });
    this.resizeObserver.observe(el);
  }
  componentWillUnmount() {
    var _a, _b;
    this.teardownResizeObserver();
    // Release the underlying editor instance to avoid leaking DOM listeners /
    // language workers across mount cycles. componentDidUpdate's re-init path
    // already calls dispose(); the unmount path was missing the symmetric call.
    (_b = (_a = this.foundation.jsonViewer) === null || _a === void 0 ? void 0 : _a.dispose) === null || _b === void 0 ? void 0 : _b.call(_a);
    super.componentWillUnmount();
  }
  componentDidUpdate(prevProps) {
    var _a, _b;
    if (!(0, _isEqual2.default)(prevProps.options, this.props.options) || this.props.value !== prevProps.value) {
      this.foundation.jsonViewer.dispose();
      this.foundation.init();
      this.setupResizeObserver();
      return;
    }
    // autoWrap toggle may require attaching/detaching observer.
    if (((_a = prevProps.options) === null || _a === void 0 ? void 0 : _a.autoWrap) !== ((_b = this.props.options) === null || _b === void 0 ? void 0 : _b.autoWrap)) {
      this.setupResizeObserver();
    }
  }
  get adapter() {
    return Object.assign(Object.assign({}, super.adapter), {
      getEditorRef: () => this.editorRef.current,
      getSearchRef: () => this.searchInputRef.current,
      notifyChange: value => {
        var _a, _b;
        (_b = (_a = this.props).onChange) === null || _b === void 0 ? void 0 : _b.call(_a, value);
      },
      notifyHover: (value, el) => {
        var _a, _b;
        const res = (_b = (_a = this.props).renderTooltip) === null || _b === void 0 ? void 0 : _b.call(_a, value, el);
        return res;
      },
      notifyCustomRender: customRenderMap => {
        this.setState({
          customRenderMap
        });
      },
      setSearchOptions: key => {
        this.setState({
          searchOptions: Object.assign(Object.assign({}, this.state.searchOptions), {
            [key]: !this.state.searchOptions[key]
          })
        }, () => {
          this.searchHandler();
        });
      },
      showSearchBar: () => {
        this.setState({
          showSearchBar: !this.state.showSearchBar
        });
        this.setState({
          searchOptions: {
            caseSensitive: false,
            wholeWord: false,
            regex: false
          }
        });
      }
    });
  }
  getValue() {
    return this.foundation.jsonViewer.getModel().getValue();
  }
  format() {
    this.foundation.jsonViewer.format();
  }
  search(searchText, caseSensitive, wholeWord, regex) {
    this.foundation.search(searchText, caseSensitive, wholeWord, regex);
  }
  getSearchResults() {
    return this.foundation.getSearchResults();
  }
  prevSearch(step) {
    this.foundation.prevSearch(step);
  }
  nextSearch(step) {
    this.foundation.nextSearch(step);
  }
  replace(replaceText) {
    this.foundation.replace(replaceText);
  }
  replaceAll(replaceText) {
    this.foundation.replaceAll(replaceText);
  }
  getStyle() {
    const {
      width,
      height
    } = this.props;
    return {
      width,
      height
    };
  }
  renderSearchBox() {
    return /*#__PURE__*/_react.default.createElement("div", {
      className: `${prefixCls}-search-bar-container`,
      style: {
        position: 'absolute',
        top: 20,
        right: 20
      }
    }, this.renderSearchBar(), this.renderReplaceBar());
  }
  renderSearchOptions() {
    const searchOptionItems = [{
      key: 'caseSensitive',
      icon: _semiIcons.IconCaseSensitive
    }, {
      key: 'regex',
      icon: _semiIcons.IconRegExp
    }, {
      key: 'wholeWord',
      icon: _semiIcons.IconWholeWord
    }];
    return /*#__PURE__*/_react.default.createElement("ul", {
      className: `${prefixCls}-search-options`
    }, searchOptionItems.map(_ref => {
      let {
        key,
        icon: Icon
      } = _ref;
      return /*#__PURE__*/_react.default.createElement("li", {
        key: key,
        className: (0, _classnames.default)(`${prefixCls}-search-options-item`, {
          [`${prefixCls}-search-options-item-active`]: this.state.searchOptions[key]
        })
      }, /*#__PURE__*/_react.default.createElement(Icon, {
        onClick: () => this.changeSearchOptions(key)
      }));
    }));
  }
  renderSearchBar() {
    return /*#__PURE__*/_react.default.createElement(_localeConsumer.default, {
      componentName: "JsonViewer"
    }, (locale, localeCode) => (/*#__PURE__*/_react.default.createElement("div", {
      className: `${prefixCls}-search-bar`
    }, /*#__PURE__*/_react.default.createElement(_input.default, {
      placeholder: locale.search,
      className: `${prefixCls}-search-bar-input`,
      onChange: (_value, e) => {
        var _a;
        e.preventDefault();
        if (!this.isComposing) {
          this.searchHandler();
        }
        (_a = this.searchInputRef.current) === null || _a === void 0 ? void 0 : _a.focus();
      },
      onCompositionStart: () => {
        this.isComposing = true;
      },
      onCompositionEnd: () => {
        var _a;
        this.isComposing = false;
        this.searchHandler();
        (_a = this.searchInputRef.current) === null || _a === void 0 ? void 0 : _a.focus();
      },
      ref: this.searchInputRef
    }), this.renderSearchOptions(), /*#__PURE__*/_react.default.createElement(_buttonGroup.default, null, /*#__PURE__*/_react.default.createElement(_button.default, {
      icon: /*#__PURE__*/_react.default.createElement(_semiIcons.IconChevronLeft, null),
      onClick: e => {
        e.preventDefault();
        this.foundation.prevSearch();
      }
    }), /*#__PURE__*/_react.default.createElement(_button.default, {
      icon: /*#__PURE__*/_react.default.createElement(_semiIcons.IconChevronRight, null),
      onClick: e => {
        e.preventDefault();
        this.foundation.nextSearch();
      }
    })), /*#__PURE__*/_react.default.createElement(_button.default, {
      icon: /*#__PURE__*/_react.default.createElement(_semiIcons.IconClose, null),
      size: "small",
      theme: 'borderless',
      type: 'tertiary',
      onClick: () => this.foundation.showSearchBar()
    }))));
  }
  renderReplaceBar() {
    const {
      readOnly
    } = this.props.options;
    return /*#__PURE__*/_react.default.createElement(_localeConsumer.default, {
      componentName: "JsonViewer"
    }, (locale, localeCode) => (/*#__PURE__*/_react.default.createElement("div", {
      className: `${prefixCls}-replace-bar`
    }, /*#__PURE__*/_react.default.createElement(_input.default, {
      placeholder: locale.replace,
      className: `${prefixCls}-replace-bar-input`,
      onChange: (value, e) => {
        e.preventDefault();
      },
      ref: this.replaceInputRef
    }), /*#__PURE__*/_react.default.createElement(_button.default, {
      style: {
        width: 'fit-content'
      },
      disabled: readOnly,
      onClick: () => {
        var _a;
        const value = (_a = this.replaceInputRef.current) === null || _a === void 0 ? void 0 : _a.value;
        this.foundation.replace(value);
      }
    }, locale.replace), /*#__PURE__*/_react.default.createElement(_button.default, {
      style: {
        width: 'fit-content'
      },
      disabled: readOnly,
      onClick: () => {
        var _a;
        const value = (_a = this.replaceInputRef.current) === null || _a === void 0 ? void 0 : _a.value;
        this.foundation.replaceAll(value);
      }
    }, locale.replaceAll))));
  }
  render() {
    let isDragging = false;
    const _a = this.props,
      {
        width,
        className,
        style,
        showSearch = true,
        limitSearchButtonBounds,
        renderSearchButton
      } = _a,
      rest = __rest(_a, ["width", "className", "style", "showSearch", "limitSearchButtonBounds", "renderSearchButton"]);
    // Default search button
    const defaultSearchButton = /*#__PURE__*/_react.default.createElement(_dragMove.default, {
      constrainer: limitSearchButtonBounds ? 'parent' : undefined,
      onMouseDown: () => {
        isDragging = false;
      },
      onMouseMove: () => {
        isDragging = true;
      }
    }, /*#__PURE__*/_react.default.createElement("div", {
      style: {
        position: 'absolute',
        top: 0,
        left: width
      }
    }, !this.state.showSearchBar ? (/*#__PURE__*/_react.default.createElement(_button.default, {
      className: `${prefixCls}-search-bar-trigger`,
      onClick: e => {
        e.preventDefault();
        if (isDragging) {
          e.stopPropagation();
          e.preventDefault();
          return;
        }
        this.foundation.showSearchBar();
      },
      icon: /*#__PURE__*/_react.default.createElement(_semiIcons.IconSearch, null),
      style: {
        position: 'absolute',
        top: 20,
        right: 20
      }
    })) : this.renderSearchBox()));
    // Search controls for custom render
    const searchControls = {
      showSearchBar: this.state.showSearchBar,
      onToggleSearchBar: () => this.foundation.showSearchBar(),
      onSearch: (text, caseSensitive, wholeWord, regex) => {
        this.foundation.search(text, caseSensitive, wholeWord, regex);
      },
      onPrevSearch: () => this.foundation.prevSearch(),
      onNextSearch: () => this.foundation.nextSearch(),
      onReplace: text => this.foundation.replace(text),
      onReplaceAll: text => this.foundation.replaceAll(text)
    };
    return /*#__PURE__*/_react.default.createElement(_react.default.Fragment, null, /*#__PURE__*/_react.default.createElement("div", Object.assign({
      style: Object.assign(Object.assign(Object.assign({}, this.getStyle()), {
        position: 'relative'
      }), style),
      className: className
    }, this.getDataAttr(rest)), /*#__PURE__*/_react.default.createElement("div", {
      style: Object.assign({}, this.getStyle()),
      ref: this.editorRef,
      className: (0, _classnames.default)(prefixCls, `${prefixCls}-background`)
    }), showSearch && (renderSearchButton ? renderSearchButton(defaultSearchButton, searchControls) : defaultSearchButton)), Array.from(this.state.customRenderMap.entries()).map(_ref2 => {
      let [key, value] = _ref2;
      // key.innerHTML = '';
      return /*#__PURE__*/(0, _reactDom.createPortal)(value, key);
    }));
  }
}
JsonViewerCom.defaultProps = {
  width: 400,
  height: 400,
  value: '',
  options: {
    readOnly: false,
    autoWrap: true
  }
};
var _default = exports.default = JsonViewerCom;