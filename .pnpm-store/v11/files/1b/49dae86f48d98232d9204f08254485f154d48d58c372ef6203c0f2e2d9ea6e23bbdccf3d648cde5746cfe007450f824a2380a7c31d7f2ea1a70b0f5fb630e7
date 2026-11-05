import React from 'react';
const FormStateContext = /*#__PURE__*/React.createContext({});
FormStateContext.displayName = 'FormState';
const FormApiContext = /*#__PURE__*/React.createContext({});
FormApiContext.displayName = 'FormApi';
const FormUpdaterContext = /*#__PURE__*/React.createContext({});
FormUpdaterContext.displayName = 'FormUpdater';
const ArrayFieldContext = /*#__PURE__*/React.createContext({
  shouldUseInitValue: true,
  // Whether the current subtree is rendered inside an <ArrayField/>.
  // Used by withField to disable `keepState` semantics inside an ArrayField,
  // because positional field paths (e.g. people[0].name) shift on remove,
  // which conflicts with the "preserve unmounted state by path" model.
  inArrayField: false
});
export { FormStateContext, FormApiContext, FormUpdaterContext, ArrayFieldContext };