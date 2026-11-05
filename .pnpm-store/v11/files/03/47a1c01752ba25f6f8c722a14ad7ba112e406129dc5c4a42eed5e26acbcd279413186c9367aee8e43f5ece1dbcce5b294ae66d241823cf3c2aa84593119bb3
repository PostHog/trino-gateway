import React, { useMemo } from 'react';
import Context from './table-context';
const TableContextProvider = _ref => {
  let {
    children,
    anyColumnFixed,
    flattenedColumns,
    tableWidth,
    headWidths,
    setHeadWidths,
    getHeadWidths,
    getCellWidths,
    handleRowExpanded,
    renderExpandIcon,
    renderSelection,
    getVirtualizedListRef,
    setBodyHasScrollbar,
    direction,
    handleRowSelection,
    headerStyle,
    rowSpanHover
  } = _ref;
  const tableContextValue = useMemo(() => ({
    anyColumnFixed,
    flattenedColumns,
    renderExpandIcon,
    renderSelection,
    setHeadWidths,
    getHeadWidths,
    getCellWidths,
    headWidths,
    tableWidth,
    handleRowExpanded,
    getVirtualizedListRef,
    setBodyHasScrollbar,
    direction,
    handleRowSelection,
    headerStyle,
    rowSpanHover
  }), [anyColumnFixed, flattenedColumns, renderExpandIcon, renderSelection, setHeadWidths, getHeadWidths, getCellWidths, headWidths, tableWidth, handleRowExpanded, getVirtualizedListRef, setBodyHasScrollbar, direction, handleRowSelection, headerStyle, rowSpanHover]);
  return /*#__PURE__*/React.createElement(Context.Provider, {
    value: tableContextValue
  }, children);
};
export default TableContextProvider;