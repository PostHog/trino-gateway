"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.defaultResponsiveMap = exports.default = exports.ConfigConsumer = void 0;
var _react = _interopRequireDefault(require("react"));
var _propTypes = _interopRequireDefault(require("prop-types"));
var _constants = require("@douyinfe/semi-foundation/lib/cjs/base/constants");
var _warning = _interopRequireDefault(require("@douyinfe/semi-foundation/lib/cjs/utils/warning"));
var _zh_CN = _interopRequireDefault(require("../locale/source/zh_CN"));
var _context = _interopRequireDefault(require("./context"));
var _utils = require("../_utils");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
var __rest = void 0 && (void 0).__rest || function (s, e) {
  var t = {};
  for (var p in s) if (Object.prototype.hasOwnProperty.call(s, p) && e.indexOf(p) < 0) t[p] = s[p];
  if (s != null && typeof Object.getOwnPropertySymbols === "function") for (var i = 0, p = Object.getOwnPropertySymbols(s); i < p.length; i++) {
    if (e.indexOf(p[i]) < 0 && Object.prototype.propertyIsEnumerable.call(s, p[i])) t[p[i]] = s[p[i]];
  }
  return t;
};
/**
 * Default responsive map configuration
 * Can be accessed via ConfigProvider.defaultResponsiveMap
 */
const defaultResponsiveMap = exports.defaultResponsiveMap = {
  xs: '(max-width: 575px)',
  sm: '(min-width: 576px)',
  md: '(min-width: 768px)',
  lg: '(min-width: 992px)',
  xl: '(min-width: 1200px)',
  xxl: '(min-width: 1600px)'
};
const ConfigConsumer = exports.ConfigConsumer = _context.default.Consumer;
class ConfigProvider extends _react.default.Component {
  constructor(props) {
    super(props);
    this.unRegisters = [];
    this.hasRegisteredMediaQueries = false;
    this.hasWarnedResponsiveObserve = false;
    this.screensListeners = new Set();
    this.changeListeners = new Set();
    /**
     * Synchronous source of truth for current breakpoint matches.
     * `setState` is async, so reading `this.state.screens` immediately after
     * registering listeners returns stale values. Subscriber callbacks read
     * from this ref to always get the freshest snapshot.
     */
    this.currentScreensRef = null;
    this.ensureMediaQueriesRegistered = () => {
      if (!this.props.responsiveObserve) {
        if (!this.hasWarnedResponsiveObserve) {
          this.hasWarnedResponsiveObserve = true;
          const shouldWarn = typeof process !== 'undefined' && !!process.env && process.env.NODE_ENV !== 'production';
          (0, _warning.default)(shouldWarn, '[Semi] ConfigProvider responsive observing is disabled by default. ' + 'Set <ConfigProvider responsiveObserve> to enable breakpoint subscriptions.');
        }
        return;
      }
      const hasAnySubscriber = this.screensListeners.size > 0 || this.changeListeners.size > 0;
      if (!hasAnySubscriber) {
        return;
      }
      if (this.hasRegisteredMediaQueries) {
        return;
      }
      this.registerMediaQueries();
    };
    /**
     * Register media query listeners.
     *
     * To avoid stale-state bug in immediate subscriber callbacks, we
     * synchronously read all `matchMedia(...).matches` once *before* attaching
     * the change listeners, write the result to both `currentScreensRef` and
     * `state.screens` in a single batched setState, and only then start
     * tracking changes (with `callInInit: false` so we don't double-fire).
     */
    this.registerMediaQueries = () => {
      if (this.hasRegisteredMediaQueries) {
        return;
      }
      const responsiveMap = this.props.responsiveMap || defaultResponsiveMap;
      const breakpointKeys = Object.keys(responsiveMap);
      const initialScreens = {
        xs: false,
        sm: false,
        md: false,
        lg: false,
        xl: false,
        xxl: false
      };
      if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
        breakpointKeys.forEach(screen => {
          initialScreens[screen] = window.matchMedia(responsiveMap[screen]).matches;
        });
      }
      this.currentScreensRef = initialScreens;
      this.unRegisters = breakpointKeys.map(screen => (0, _utils.registerMediaQuery)(responsiveMap[screen], {
        match: () => {
          this.updateScreen(screen, true);
        },
        unmatch: () => {
          this.updateScreen(screen, false);
        },
        callInInit: false
      }));
      this.hasRegisteredMediaQueries = true;
      this.setState({
        screens: initialScreens
      });
    };
    /**
     * Unregister all media query listeners
     */
    this.unregisterMediaQueries = () => {
      this.unRegisters.forEach(unRegister => unRegister());
      this.unRegisters = [];
      this.hasRegisteredMediaQueries = false;
      this.currentScreensRef = null;
    };
    /**
     * Update screen state and notify listeners
     */
    this.updateScreen = (screen, matches) => {
      if (this.currentScreensRef && this.currentScreensRef[screen] !== matches) {
        this.currentScreensRef = Object.assign(Object.assign({}, this.currentScreensRef), {
          [screen]: matches
        });
      }
      this.setState(prevState => {
        if (prevState.screens[screen] === matches) {
          return null;
        }
        return {
          screens: Object.assign(Object.assign({}, prevState.screens), {
            [screen]: matches
          })
        };
      }, () => {
        this.notifyListeners(screen, matches);
      });
    };
    /**
     * Notify all registered listeners
     */
    this.notifyListeners = (changedScreen, match) => {
      const {
        screens
      } = this.state;
      // full screens subscriptions
      this.screensListeners.forEach(listener => {
        listener(screens);
      });
      // single change subscriptions
      if (changedScreen != null && match != null) {
        this.changeListeners.forEach(_ref => {
          let {
            breakpoints,
            callback
          } = _ref;
          if (!breakpoints || breakpoints.includes(changedScreen)) {
            callback(changedScreen, match);
          }
        });
      }
    };
    /**
     * Subscribe to breakpoint changes
     * @param callback Function to call when breakpoint changes
     * @returns Unsubscribe function
     */
    this.handleBreakpoint = (arg1, arg2) => {
      var _a, _b;
      // onBreakpoint(callback)
      if (typeof arg1 === 'function') {
        const cb = arg1;
        this.screensListeners.add(cb);
        this.ensureMediaQueriesRegistered();
        // Read from currentScreensRef so we deliver the freshly-computed
        // matches (set synchronously inside registerMediaQueries) instead
        // of the still-async this.state.screens.
        const initialScreens = (_a = this.currentScreensRef) !== null && _a !== void 0 ? _a : this.state.screens;
        cb(initialScreens);
        return () => {
          this.screensListeners.delete(cb);
          if (this.props.responsiveObserve && this.screensListeners.size === 0 && this.changeListeners.size === 0) {
            this.unregisterMediaQueries();
          }
        };
      }
      // onBreakpoint(breakpoints, callback)
      const breakpoints = Array.isArray(arg1) ? arg1 : undefined;
      const cb = arg2;
      const entry = {
        breakpoints,
        callback: cb
      };
      this.changeListeners.add(entry);
      this.ensureMediaQueriesRegistered();
      const initialScreens = (_b = this.currentScreensRef) !== null && _b !== void 0 ? _b : this.state.screens;
      if (breakpoints && typeof cb === 'function') {
        breakpoints.forEach(bp => {
          cb(bp, initialScreens[bp]);
        });
      }
      return () => {
        this.changeListeners.delete(entry);
        // if no subscribers remain, unregister to save resources
        if (this.props.responsiveObserve && this.screensListeners.size === 0 && this.changeListeners.size === 0) {
          this.unregisterMediaQueries();
        }
      };
    };
    this.state = {
      screens: {
        xs: false,
        sm: false,
        md: false,
        lg: false,
        xl: false,
        xxl: false
      }
    };
  }
  componentDidMount() {
    // lazy register on demand (first subscription)
  }
  componentDidUpdate(prevProps) {
    // If toggle switched off, ensure unregister
    if (prevProps.responsiveObserve && !this.props.responsiveObserve) {
      this.unregisterMediaQueries();
    }
    // Re-register media queries if responsiveMap changes (only if already registered)
    if (this.hasRegisteredMediaQueries && prevProps.responsiveMap !== this.props.responsiveMap) {
      this.unregisterMediaQueries();
      this.registerMediaQueries();
    }
    // If toggle switched on and there are existing subscriptions, ensure register
    if (!prevProps.responsiveObserve && this.props.responsiveObserve) {
      this.ensureMediaQueriesRegistered();
    }
  }
  componentWillUnmount() {
    this.unregisterMediaQueries();
    this.screensListeners.clear();
    this.changeListeners.clear();
  }
  renderChildren() {
    const {
      direction,
      children
    } = this.props;
    if (direction === 'rtl') {
      return /*#__PURE__*/_react.default.createElement("div", {
        className: `${_constants.BASE_CLASS_PREFIX}-rtl`
      }, children);
    }
    return children;
  }
  render() {
    const _a = this.props,
      {
        children,
        direction,
        responsiveMap
      } = _a,
      rest = __rest(_a, ["children", "direction", "responsiveMap"]);
    const {
      screens
    } = this.state;
    return /*#__PURE__*/_react.default.createElement(_context.default.Provider, {
      value: Object.assign(Object.assign({}, rest), {
        direction,
        // internal values should not be overridden by props
        responsiveMap: responsiveMap || defaultResponsiveMap,
        onBreakpoint: this.handleBreakpoint,
        screens
      })
    }, this.renderChildren());
  }
}
exports.default = ConfigProvider;
ConfigProvider.propTypes = {
  locale: _propTypes.default.object,
  timeZone: _propTypes.default.oneOfType([_propTypes.default.string, _propTypes.default.number]),
  getPopupContainer: _propTypes.default.func,
  direction: _propTypes.default.oneOf(['ltr', 'rtl']),
  responsiveMap: _propTypes.default.object,
  responsiveObserve: _propTypes.default.bool
};
ConfigProvider.defaultProps = {
  locale: _zh_CN.default,
  direction: 'ltr',
  responsiveObserve: false
};
/**
 * Default responsive map - static property for backward compatibility
 */
ConfigProvider.defaultResponsiveMap = defaultResponsiveMap;