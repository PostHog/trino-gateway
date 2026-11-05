"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.default = void 0;
var _react = require("react");
const EMPTY_FORM_STATE = {
  values: {},
  errors: {},
  touched: {}
};
/**
 * useForm - create a FormApi usable outside of Form component
 *
 * Usage:
 *   const [formApi, formState, formValues] = Form.useForm();
 *   <Form form={formApi}>...</Form>
 *
 * @returns [formApi, formState, values]
 */
function useForm() {
  const [formState, setFormState] = (0, _react.useState)(EMPTY_FORM_STATE);
  const realFormApiRef = (0, _react.useRef)(null);
  const proxyRef = (0, _react.useRef)(null);
  const bind = (0, _react.useCallback)(api => {
    realFormApiRef.current = api;
    if (proxyRef.current) {
      proxyRef.current.__realApi = api;
    }
    setFormState(api.getFormState());
  }, []);
  const unbind = (0, _react.useCallback)(() => {
    realFormApiRef.current = null;
    if (proxyRef.current) {
      proxyRef.current.__realApi = null;
    }
    setFormState(EMPTY_FORM_STATE);
  }, []);
  const updateState = (0, _react.useCallback)(newState => {
    setFormState(prev => {
      if (prev === newState) {
        return prev;
      }
      return newState;
    });
  }, []);
  if (!proxyRef.current) {
    const internals = {
      __realApi: null,
      __bind: bind,
      __unbind: unbind,
      __updateState: updateState
    };
    const proxy = new Proxy(internals, {
      get(target, prop) {
        if (prop.startsWith('__')) {
          return target[prop];
        }
        const realApi = target.__realApi;
        if (!realApi) {
          if (typeof prop === 'string') {
            return function () {
              console.warn(`[Semi Form] FormApi.${prop}() is called before Form component is mounted. ` + `Please ensure the Form component is rendered first.`);
              return undefined;
            };
          }
          return undefined;
        }
        const value = realApi[prop];
        if (typeof value === 'function') {
          return value.bind(realApi);
        }
        return value;
      },
      set(target, prop, value) {
        if (prop.startsWith('__')) {
          target[prop] = value;
          return true;
        }
        return false;
      }
    });
    proxyRef.current = proxy;
  } else {
    proxyRef.current.__bind = bind;
    proxyRef.current.__unbind = unbind;
    proxyRef.current.__updateState = updateState;
  }
  const values = formState.values;
  return [proxyRef.current, formState, values];
}
var _default = exports.default = useForm;