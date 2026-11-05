"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.default = exports.ToastFactory = void 0;
var _react = _interopRequireDefault(require("react"));
var _reactRender = require("../_utils/reactRender");
var _propTypes = _interopRequireDefault(require("prop-types"));
var _toastListFoundation = _interopRequireDefault(require("@douyinfe/semi-foundation/lib/cjs/toast/toastListFoundation"));
var _constants = require("@douyinfe/semi-foundation/lib/cjs/toast/constants");
var _baseComponent = _interopRequireDefault(require("../_base/baseComponent"));
var _toast = _interopRequireDefault(require("./toast"));
require("@douyinfe/semi-foundation/lib/cjs/toast/toast.css");
var _uuid = _interopRequireDefault(require("@douyinfe/semi-foundation/lib/cjs/utils/uuid"));
var _useToast = _interopRequireDefault(require("./useToast"));
var _cssAnimation = _interopRequireDefault(require("../_cssAnimation"));
var _classnames = _interopRequireDefault(require("classnames"));
var _semiGlobal = _interopRequireDefault(require("../_utils/semi-global"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
const createBaseToast = () => {
  var _a;
  return _a = class ToastList extends _baseComponent.default {
    constructor(props) {
      super(props);
      this.stack = false;
      this.innerWrapperRef = /*#__PURE__*/_react.default.createRef();
      this.handleMouseEnter = e => {
        if (this.stack) {
          this.foundation.handleMouseInSideChange(true);
        }
      };
      this.handleMouseLeave = e => {
        var _a;
        if (this.stack) {
          const height = (_a = this.foundation.getInputWrapperRect()) === null || _a === void 0 ? void 0 : _a.height;
          if (height) {
            this.foundation.handleMouseInSideChange(false);
          }
        }
      };
      this.state = {
        list: [],
        removedItems: [],
        updatedItems: [],
        mouseInSide: false
      };
      this.foundation = new _toastListFoundation.default(this.adapter);
    }
    get adapter() {
      return Object.assign(Object.assign({}, super.adapter), {
        updateToast: (list, removedItems, updatedItems) => {
          this.setState({
            list,
            removedItems,
            updatedItems
          });
        },
        handleMouseInSideChange: mouseInSide => {
          this.setState({
            mouseInSide
          });
        },
        getInputWrapperRect: () => {
          var _a;
          return (_a = this.innerWrapperRef.current) === null || _a === void 0 ? void 0 : _a.getBoundingClientRect();
        }
      });
    }
    static create(opts) {
      var _a, _b, _c;
      const id = (_a = opts.id) !== null && _a !== void 0 ? _a : (0, _uuid.default)('toast');
      // this.id = id;
      // Get global config for Toast
      const globalConfig = ((_c = (_b = _semiGlobal.default === null || _semiGlobal.default === void 0 ? void 0 : _semiGlobal.default.config) === null || _b === void 0 ? void 0 : _b.overrideDefaultProps) === null || _c === void 0 ? void 0 : _c.Toast) || {};
      // Merge configs with priority: opts > globalConfig > defaultOpts
      const mergedOpts = Object.assign(Object.assign(Object.assign({}, ToastList.defaultOpts), globalConfig), opts);
      if (!ToastList.ref) {
        const div = document.createElement('div');
        if (!this.wrapperId) {
          this.wrapperId = (0, _uuid.default)('toast-wrapper').slice(0, 26);
        }
        div.className = _constants.cssClasses.WRAPPER;
        div.id = this.wrapperId;
        div.style.zIndex = String(typeof opts.zIndex === 'number' ? opts.zIndex : mergedOpts.zIndex);
        ['top', 'left', 'bottom', 'right'].map(pos => {
          if (pos in mergedOpts) {
            const val = mergedOpts[pos];
            div.style[pos] = typeof val === 'number' ? `${val}px` : val;
          }
        });
        // document.body.appendChild(div);
        if (mergedOpts.getPopupContainer) {
          const container = mergedOpts.getPopupContainer();
          container.appendChild(div);
        } else {
          document.body.appendChild(div);
        }
        (0, _reactRender.render)(/*#__PURE__*/_react.default.createElement(ToastList, {
          ref: instance => {
            if (instance) {
              ToastList.ref = instance;
              instance.add(Object.assign(Object.assign({}, mergedOpts), {
                id
              }));
              instance.stack = Boolean(mergedOpts.stack);
            }
          }
        }), div);
      } else {
        const node = document.querySelector(`#${this.wrapperId}`);
        ['top', 'left', 'bottom', 'right'].map(pos => {
          if (pos in mergedOpts) {
            node.style[pos] = typeof mergedOpts[pos] === 'number' ? `${mergedOpts[pos]}px` : mergedOpts[pos];
          }
        });
        if (Boolean(mergedOpts.stack) !== ToastList.ref.stack) {
          ToastList.ref.stack = Boolean(mergedOpts.stack);
        }
        if (ToastList.ref.has(id)) {
          ToastList.ref.update(id, Object.assign(Object.assign({}, mergedOpts), {
            id
          }));
        } else {
          ToastList.ref.add(Object.assign(Object.assign({}, mergedOpts), {
            id
          }));
        }
      }
      return id;
    }
    static close(id) {
      if (ToastList.ref) {
        ToastList.ref.remove(id);
      }
    }
    static destroyAll() {
      var _a;
      if (ToastList.ref) {
        ToastList.ref.destroyAll();
        const wrapper = document.querySelector(`#${this.wrapperId}`);
        if (wrapper) {
          (0, _reactRender.unmount)(wrapper);
          (_a = wrapper.parentNode) === null || _a === void 0 ? void 0 : _a.removeChild(wrapper);
        }
        ToastList.ref = null;
        this.wrapperId = null;
      }
    }
    static getWrapperId() {
      return this.wrapperId;
    }
    static info(opts) {
      var _a, _b;
      if (typeof opts === 'string') {
        opts = {
          content: opts
        };
      }
      // Merge with global config
      const globalConfig = ((_b = (_a = _semiGlobal.default === null || _semiGlobal.default === void 0 ? void 0 : _semiGlobal.default.config) === null || _a === void 0 ? void 0 : _a.overrideDefaultProps) === null || _b === void 0 ? void 0 : _b.Toast) || {};
      return this.create(Object.assign(Object.assign(Object.assign(Object.assign({}, ToastList.defaultOpts), globalConfig), opts), {
        type: 'info'
      }));
    }
    static warning(opts) {
      var _a, _b;
      if (typeof opts === 'string') {
        opts = {
          content: opts
        };
      }
      // Merge with global config
      const globalConfig = ((_b = (_a = _semiGlobal.default === null || _semiGlobal.default === void 0 ? void 0 : _semiGlobal.default.config) === null || _a === void 0 ? void 0 : _a.overrideDefaultProps) === null || _b === void 0 ? void 0 : _b.Toast) || {};
      return this.create(Object.assign(Object.assign(Object.assign(Object.assign({}, ToastList.defaultOpts), globalConfig), opts), {
        type: 'warning'
      }));
    }
    static error(opts) {
      var _a, _b;
      if (typeof opts === 'string') {
        opts = {
          content: opts
        };
      }
      // Merge with global config
      const globalConfig = ((_b = (_a = _semiGlobal.default === null || _semiGlobal.default === void 0 ? void 0 : _semiGlobal.default.config) === null || _a === void 0 ? void 0 : _a.overrideDefaultProps) === null || _b === void 0 ? void 0 : _b.Toast) || {};
      return this.create(Object.assign(Object.assign(Object.assign(Object.assign({}, ToastList.defaultOpts), globalConfig), opts), {
        type: 'error'
      }));
    }
    static success(opts) {
      var _a, _b;
      if (typeof opts === 'string') {
        opts = {
          content: opts
        };
      }
      // Merge with global config
      const globalConfig = ((_b = (_a = _semiGlobal.default === null || _semiGlobal.default === void 0 ? void 0 : _semiGlobal.default.config) === null || _a === void 0 ? void 0 : _a.overrideDefaultProps) === null || _b === void 0 ? void 0 : _b.Toast) || {};
      return this.create(Object.assign(Object.assign(Object.assign(Object.assign({}, ToastList.defaultOpts), globalConfig), opts), {
        type: 'success'
      }));
    }
    static config(opts) {
      ['top', 'left', 'bottom', 'right'].forEach(pos => {
        if (pos in opts) {
          ToastList.defaultOpts[pos] = opts[pos];
        }
      });
      if (typeof opts.theme === 'string' && _constants.strings.themes.includes(opts.theme)) {
        ToastList.defaultOpts.theme = opts.theme;
      }
      if (typeof opts.zIndex === 'number') {
        ToastList.defaultOpts.zIndex = opts.zIndex;
      }
      if (typeof opts.duration === 'number') {
        ToastList.defaultOpts.duration = opts.duration;
      }
      if (typeof opts.getPopupContainer === 'function') {
        ToastList.defaultOpts.getPopupContainer = opts.getPopupContainer;
      }
    }
    has(id) {
      return this.foundation.hasToast(id);
    }
    add(opts) {
      return this.foundation.addToast(opts);
    }
    update(id, opts) {
      return this.foundation.updateToast(id, opts);
    }
    remove(id) {
      return this.foundation.removeToast(id);
    }
    destroyAll() {
      return this.foundation.destroyAll();
    }
    render() {
      let {
        list
      } = this.state;
      const {
        removedItems,
        updatedItems
      } = this.state;
      list = Array.from(new Set([...list, ...removedItems]));
      const updatedIds = updatedItems.map(_ref => {
        let {
          id
        } = _ref;
        return id;
      });
      const refFn = toast => {
        var _a;
        if (((_a = toast === null || toast === void 0 ? void 0 : toast.foundation) === null || _a === void 0 ? void 0 : _a._id) && updatedIds.includes(toast.foundation._id)) {
          toast.foundation.restartCloseTimer();
        }
      };
      return /*#__PURE__*/_react.default.createElement(_react.default.Fragment, null, /*#__PURE__*/_react.default.createElement("div", {
        className: (0, _classnames.default)({
          [`${_constants.cssClasses.PREFIX}-innerWrapper`]: true,
          [`${_constants.cssClasses.PREFIX}-innerWrapper-hover`]: this.state.mouseInSide
        }),
        ref: this.innerWrapperRef,
        onMouseEnter: this.handleMouseEnter,
        onMouseLeave: this.handleMouseLeave
      }, list.map((item, index) => {
        const isRemoved = removedItems.find(removedItem => removedItem.id === item.id) !== undefined;
        const animationState = isRemoved ? "leave" : "enter";
        return /*#__PURE__*/_react.default.createElement(_cssAnimation.default, {
          key: item.id,
          motion: item.motion,
          animationState: animationState,
          startClassName: isRemoved ? `${_constants.cssClasses.PREFIX}-animation-hide` : `${_constants.cssClasses.PREFIX}-animation-show`,
          onAnimationEnd: _stoppedByAnother => {
            // Only act on the leave animation end.
            // Guarding by the animationState captured at render time prevents:
            // - enter animation end firing after the toast is later marked removed (would otherwise remove too early)
            if (animationState !== 'leave') {
              return;
            }
            // When leave animation ends, remove it from removedItems.
            // This ensures:
            // 1) No extra re-mount during closing animation
            // 2) Toast/content will finally unmount after animation (avoid hidden DOM/memory retention)
            // 3) ToastFoundation.destroy() runs to clear timers
            this.setState(prev => {
              var _a;
              // If this toast is no longer marked as removed (e.g. updated/reopened), do nothing
              if (!((_a = prev.removedItems) === null || _a === void 0 ? void 0 : _a.some(removed => removed.id === item.id))) {
                return null;
              }
              return Object.assign(Object.assign({}, prev), {
                removedItems: prev.removedItems.filter(removed => removed.id !== item.id)
              });
            });
          }
        }, _ref2 => {
          let {
            animationClassName,
            animationEventsNeedBind,
            isAnimating
          } = _ref2;
          // Keep Toast component instance during leave animation to avoid re-mount.
          // Final unmount is triggered by onAnimationEnd above (removing from removedItems).
          return /*#__PURE__*/_react.default.createElement(_toast.default, Object.assign({}, item, {
            stack: this.stack,
            stackExpanded: this.state.mouseInSide,
            positionInList: {
              length: list.length,
              index
            },
            className: (0, _classnames.default)({
              [item.className]: Boolean(item.className),
              [animationClassName]: true
            })
          }, animationEventsNeedBind, {
            style: Object.assign({}, item.style),
            close: id => this.remove(id),
            ref: refFn
          }));
        });
      })));
    }
  }, _a.defaultOpts = {
    motion: true,
    zIndex: 1010,
    content: ''
  }, _a.propTypes = {
    content: _propTypes.default.node,
    duration: _propTypes.default.number,
    onClose: _propTypes.default.func,
    icon: _propTypes.default.node,
    direction: _propTypes.default.oneOf(_constants.strings.directions),
    stack: _propTypes.default.bool
  }, _a.defaultProps = {}, _a;
};
class ToastFactory {
  static create(config) {
    const newToast = createBaseToast();
    newToast.useToast = _useToast.default;
    config && newToast.config(config);
    return newToast;
  }
}
exports.ToastFactory = ToastFactory;
var _default = exports.default = ToastFactory.create();