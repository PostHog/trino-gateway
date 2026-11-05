"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.default = void 0;
var _debounce2 = _interopRequireDefault(require("lodash/debounce"));
var _foundation = _interopRequireDefault(require("../base/foundation"));
var _isPromise = _interopRequireDefault(require("../utils/isPromise"));
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
class ModalFoundation extends _foundation.default {
  constructor(adapter) {
    super(Object.assign({}, adapter));
    this._debouncedOk = (0, _debounce2.default)(e => {
      this._invokeOk(e);
    }, 100, {
      leading: true,
      trailing: false
    });
    this._debouncedCancel = (0, _debounce2.default)(e => {
      this._invokeCancel(e);
    }, 100, {
      leading: true,
      trailing: false
    });
    this._lastCancelTarget = null;
    this._lastOkTarget = null;
    this.toggleDisplayNone = (displayNone, callback) => {
      this._adapter.toggleDisplayNone(displayNone, callback);
    };
  }
  destroy() {
    this._debouncedOk.cancel();
    this._debouncedCancel.cancel();
    this.afterHide();
  }
  handleCancel(e) {
    var _a, _b;
    const target = (_b = (_a = e === null || e === void 0 ? void 0 : e.currentTarget) !== null && _a !== void 0 ? _a : e === null || e === void 0 ? void 0 : e.target) !== null && _b !== void 0 ? _b : null;
    if (target !== this._lastCancelTarget) {
      this._debouncedCancel.cancel();
    }
    this._lastCancelTarget = target;
    this._debouncedCancel(e);
  }
  handleOk(e) {
    var _a, _b;
    const target = (_b = (_a = e === null || e === void 0 ? void 0 : e.currentTarget) !== null && _a !== void 0 ? _a : e === null || e === void 0 ? void 0 : e.target) !== null && _b !== void 0 ? _b : null;
    if (target !== this._lastOkTarget) {
      this._debouncedOk.cancel();
    }
    this._lastOkTarget = target;
    this._debouncedOk(e);
  }
  _invokeCancel(e) {
    var _a;
    const result = this._adapter.notifyCancel(e);
    if ((0, _isPromise.default)(result)) {
      this._adapter.setState({
        onCancelReturnPromiseStatus: "pending"
      });
      (_a = result === null || result === void 0 ? void 0 : result.then(() => {
        this._adapter.setState({
          onCancelReturnPromiseStatus: "fulfilled"
        });
      })) === null || _a === void 0 ? void 0 : _a.catch(e => {
        this._adapter.setState({
          onCancelReturnPromiseStatus: "rejected"
        });
        throw e;
      });
    }
  }
  _invokeOk(e) {
    var _a;
    const result = this._adapter.notifyOk(e);
    if ((0, _isPromise.default)(result)) {
      this._adapter.setState({
        onOKReturnPromiseStatus: "pending"
      });
      (_a = result === null || result === void 0 ? void 0 : result.then(() => {
        this._adapter.setState({
          onOKReturnPromiseStatus: "fulfilled"
        });
      })) === null || _a === void 0 ? void 0 : _a.catch(e => {
        this._adapter.setState({
          onOKReturnPromiseStatus: "rejected"
        });
        throw e;
      });
    }
  }
  beforeShow() {
    this._adapter.disabledBodyScroll();
  }
  afterHide() {
    this._adapter.enabledBodyScroll();
    this._adapter.notifyClose();
  }
  enabledBodyScroll() {
    this._adapter.enabledBodyScroll();
  }
}
exports.default = ModalFoundation;