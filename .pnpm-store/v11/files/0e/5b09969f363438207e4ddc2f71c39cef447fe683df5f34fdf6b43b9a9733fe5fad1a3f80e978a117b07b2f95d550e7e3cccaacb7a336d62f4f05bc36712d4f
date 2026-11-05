import _debounce from "lodash/debounce";
import BaseFoundation from '../base/foundation';
import isPromise from "../utils/isPromise";
export default class ModalFoundation extends BaseFoundation {
  constructor(adapter) {
    super(Object.assign({}, adapter));
    this._debouncedOk = _debounce(e => {
      this._invokeOk(e);
    }, 100, {
      leading: true,
      trailing: false
    });
    this._debouncedCancel = _debounce(e => {
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
    if (isPromise(result)) {
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
    if (isPromise(result)) {
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