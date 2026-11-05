import _pick from "lodash/pick";
import _noop from "lodash/noop";
import _isEqual from "lodash/isEqual";
var __rest = this && this.__rest || function (s, e) {
  var t = {};
  for (var p in s) if (Object.prototype.hasOwnProperty.call(s, p) && e.indexOf(p) < 0) t[p] = s[p];
  if (s != null && typeof Object.getOwnPropertySymbols === "function") for (var i = 0, p = Object.getOwnPropertySymbols(s); i < p.length; i++) {
    if (e.indexOf(p[i]) < 0 && Object.prototype.propertyIsEnumerable.call(s, p[i])) t[p[i]] = s[p[i]];
  }
  return t;
};
import React, { isValidElement, useEffect, useState } from 'react';
import cls from 'classnames';
import { IconFilter } from '@douyinfe/semi-icons';
import { cssClasses } from '@douyinfe/semi-foundation/lib/es/table/constants';
import Dropdown from '../dropdown';
import { Radio } from '../radio';
import { Checkbox } from '../checkbox';
import Button from '../button';
import Space from '../space';
import LocaleConsumer from '../locale/localeConsumer';
function renderDropdown(props) {
  let nestedElem = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : null;
  let level = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : 0;
  let locale = arguments.length > 3 ? arguments[3] : undefined;
  const {
    filterMultiple = true,
    filters = [],
    filteredValue = [],
    filterDropdownVisible,
    onSelect = _noop,
    onFilterDropdownVisibleChange = _noop,
    trigger = 'click',
    position = 'bottom',
    renderFilterDropdown,
    renderFilterDropdownItem,
    filterConfirmMode = 'immediate',
    tempFilteredValue,
    setTempFilteredValue,
    confirm,
    clear,
    close,
    reset
  } = props !== null && props !== void 0 ? props : {};
  // Determine which value to use for displaying checked state
  // In confirm mode, use tempFilteredValue; otherwise use filteredValue
  const displayValue = filterConfirmMode === 'confirm' ? tempFilteredValue !== null && tempFilteredValue !== void 0 ? tempFilteredValue : [] : filteredValue;
  const renderFilterDropdownProps = _pick(props, ['tempFilteredValue', 'setTempFilteredValue', 'confirm', 'clear', 'close', 'filters']);
  const render = typeof renderFilterDropdown === 'function' ? renderFilterDropdown(renderFilterDropdownProps) : (/*#__PURE__*/React.createElement(Dropdown.Menu, null, Array.isArray(filters) && filters.map((filter, index) => {
    const changeFn = e => {
      const domEvent = e && e.nativeEvent;
      if (domEvent) {
        // Block this event to prevent the pop-up layer from closing
        domEvent.stopImmediatePropagation();
        // Prevent bubbling and default events to prevent label click events from triggering twice
        domEvent.stopPropagation();
        domEvent.preventDefault();
      }
      // In confirm mode, update tempFilteredValue instead of calling onSelect
      if (filterConfirmMode === 'confirm') {
        const currentTempValue = tempFilteredValue !== null && tempFilteredValue !== void 0 ? tempFilteredValue : [...filteredValue];
        let values = [...currentTempValue];
        const included = values.includes(filter.value);
        const idx = values.indexOf(filter.value);
        if (idx > -1) {
          values.splice(idx, 1);
        } else if (filterMultiple) {
          values.push(filter.value);
        } else {
          values = [filter.value];
        }
        setTempFilteredValue === null || setTempFilteredValue === void 0 ? void 0 : setTempFilteredValue(values);
      } else {
        // Immediate mode: original behavior
        let values = [...filteredValue];
        const included = values.includes(filter.value);
        const idx = values.indexOf(filter.value);
        if (idx > -1) {
          values.splice(idx, 1);
        } else if (filterMultiple) {
          values.push(filter.value);
        } else {
          values = [filter.value];
        }
        return onSelect({
          value: filter.value,
          filteredValue: values,
          included: !included,
          domEvent
        });
      }
    };
    const checked = displayValue.includes(filter.value);
    const {
      text
    } = filter;
    const {
      value
    } = filter;
    const key = `${level}_${index}`;
    const dropdownItem = typeof renderFilterDropdownItem === 'function' ? renderFilterDropdownItem({
      onChange: changeFn,
      filterMultiple,
      value,
      text,
      checked,
      filteredValue: displayValue,
      level
    }) : null;
    let item = dropdownItem && /*#__PURE__*/React.isValidElement(dropdownItem) ? (/*#__PURE__*/React.cloneElement(dropdownItem, {
      key
    })) : (/*#__PURE__*/React.createElement(Dropdown.Item, {
      key: key,
      onClick: changeFn
    }, filterMultiple ? (/*#__PURE__*/React.createElement(Checkbox, {
      checked: checked
    }, text)) : (/*#__PURE__*/React.createElement(Radio, {
      checked: checked
    }, text))));
    if (Array.isArray(filter.children) && filter.children.length) {
      const childrenDropdownProps = Object.assign(Object.assign({}, props), {
        filters: filter.children,
        trigger: 'hover',
        position: 'right'
      });
      delete childrenDropdownProps.filterDropdownVisible;
      item = renderDropdown(childrenDropdownProps, item, level + 1, locale);
    }
    return item;
  }), filterConfirmMode === 'confirm' && level === 0 && (/*#__PURE__*/React.createElement("div", {
    style: {
      padding: '8px 12px',
      borderTop: '1px solid var(--semi-color-border)',
      display: 'flex',
      justifyContent: 'flex-end'
    }
  }, /*#__PURE__*/React.createElement(Space, null, /*#__PURE__*/React.createElement(Button, {
    size: "small",
    onClick: () => reset === null || reset === void 0 ? void 0 : reset()
  }, (locale === null || locale === void 0 ? void 0 : locale.resetFilter) || 'Reset'), /*#__PURE__*/React.createElement(Button, {
    size: "small",
    theme: "solid",
    onClick: () => confirm === null || confirm === void 0 ? void 0 : confirm({
      closeDropdown: true
    })
  }, (locale === null || locale === void 0 ? void 0 : locale.confirmFilter) || 'OK'))))));
  // Extract filterDropdownVisible to avoid passing it twice
  const {
      filterDropdownVisible: _
    } = props,
    restProps = __rest(props, ["filterDropdownVisible"]);
  const dropdownProps = Object.assign(Object.assign({}, restProps), {
    trigger,
    position,
    render,
    onVisibleChange: visible => onFilterDropdownVisibleChange(visible)
  });
  // Only set visible when it's controlled (not null/undefined)
  // This is important for confirm mode to work correctly
  if (filterDropdownVisible != null) {
    dropdownProps.visible = filterDropdownVisible;
  }
  return /*#__PURE__*/React.createElement(Dropdown, Object.assign({}, dropdownProps, {
    key: `Dropdown_level_${level}`,
    className: `${cssClasses.PREFIX}-column-filter-dropdown`
  }), nestedElem);
}
export default function ColumnFilter() {
  let props = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : {};
  const {
    prefixCls = cssClasses.PREFIX,
    filteredValue,
    filterIcon = 'filter',
    filterDropdownProps,
    onSelect,
    filterDropdownVisible,
    renderFilterDropdown,
    onFilterDropdownVisibleChange,
    filterConfirmMode = 'immediate'
  } = props;
  let {
    filterDropdown = null
  } = props;
  // custom filter related status
  const isFilterDropdownVisibleControlled = typeof filterDropdownVisible !== 'undefined';
  const isCustomFilterDropdown = typeof renderFilterDropdown === 'function';
  // In confirm mode, we also need to control the dropdown visible state
  const isCustomDropdownVisible = !isFilterDropdownVisibleControlled && (isCustomFilterDropdown || filterConfirmMode === 'confirm');
  const [tempFilteredValue, setTempFilteredValue] = useState(filteredValue);
  // Store the initial filtered value when dropdown opens (for reset functionality)
  const [initialFilteredValue, setInitialFilteredValue] = useState(filteredValue);
  const dropdownVisibleInitValue = isCustomDropdownVisible ? false : filterDropdownVisible;
  const [dropdownVisible, setDropdownVisible] = useState(dropdownVisibleInitValue);
  useEffect(() => {
    if (typeof filterDropdownVisible !== 'undefined') {
      setDropdownVisible(filterDropdownVisible);
    }
  }, [filterDropdownVisible]);
  useEffect(() => {
    setTempFilteredValue(filteredValue);
  }, [filteredValue]);
  const confirm = function () {
    let props = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : {};
    const newFilteredValue = (props === null || props === void 0 ? void 0 : props.filteredValue) || tempFilteredValue;
    if (!_isEqual(newFilteredValue, filteredValue)) {
      onSelect({
        filteredValue: newFilteredValue
      });
    }
    if (props.closeDropdown) {
      setDropdownVisible(false);
    }
  };
  const clear = function () {
    let props = arguments.length > 0 && arguments[0] !== undefined ? arguments[0] : {};
    setTempFilteredValue([]);
    onSelect({
      filteredValue: []
    });
    if (props.closeDropdown) {
      setDropdownVisible(false);
    }
  };
  const close = () => {
    setDropdownVisible(false);
  };
  const reset = () => {
    setTempFilteredValue(initialFilteredValue);
  };
  const handleFilterDropdownVisibleChange = visible => {
    if (isCustomDropdownVisible) {
      setDropdownVisible(visible);
    }
    // When dropdown opens in confirm mode, save the initial filtered value for reset
    if (visible && filterConfirmMode === 'confirm') {
      setInitialFilteredValue(filteredValue);
      setTempFilteredValue(filteredValue);
    }
    onFilterDropdownVisibleChange(visible);
  };
  const renderFilterDropdownProps = {
    tempFilteredValue,
    setTempFilteredValue,
    confirm,
    clear,
    close,
    reset,
    filters: props.filters
  };
  const finalCls = cls(`${prefixCls}-column-filter`, {
    on: Array.isArray(filteredValue) && filteredValue.length
  });
  let iconElem;
  if (typeof filterIcon === 'function') {
    iconElem = filterIcon(Array.isArray(filteredValue) && filteredValue.length > 0);
  } else if (/*#__PURE__*/isValidElement(filterIcon)) {
    iconElem = filterIcon;
  } else {
    iconElem = /*#__PURE__*/React.createElement("div", {
      className: finalCls
    }, '\u200b' /* ZWSP(zero-width space) */, /*#__PURE__*/React.createElement(IconFilter, {
      role: "button",
      "aria-label": "Filter data with this column",
      "aria-haspopup": "listbox",
      tabIndex: -1,
      size: "default"
    }));
  }
  const renderProps = Object.assign(Object.assign(Object.assign(Object.assign({}, props), filterDropdownProps), renderFilterDropdownProps), {
    filterDropdownVisible: isFilterDropdownVisibleControlled ? filterDropdownVisible : dropdownVisible,
    onFilterDropdownVisibleChange: handleFilterDropdownVisibleChange
  });
  return /*#__PURE__*/React.createElement(LocaleConsumer, {
    componentName: "Table"
  }, locale => {
    if (/*#__PURE__*/React.isValidElement(filterDropdown)) {
      return filterDropdown;
    }
    return renderDropdown(renderProps, iconElem, 0, locale);
  });
}