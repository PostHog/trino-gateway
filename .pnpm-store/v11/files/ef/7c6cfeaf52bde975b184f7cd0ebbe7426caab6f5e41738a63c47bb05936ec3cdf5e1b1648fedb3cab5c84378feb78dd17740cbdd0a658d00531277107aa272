"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.calcMergeType = calcMergeType;
exports.convertDataToEntities = convertDataToEntities;
exports.filter = filter;
exports.getKeyByPos = getKeyByPos;
exports.getKeyByValuePath = getKeyByValuePath;
exports.getKeysByValuePath = getKeysByValuePath;
exports.getValueOrKey = getValueOrKey;
exports.getValuePathByKey = getValuePathByKey;
exports.isValid = isValid;
exports.normalizedArr = normalizedArr;
var _get2 = _interopRequireDefault(require("lodash/get"));
var _isUndefined2 = _interopRequireDefault(require("lodash/isUndefined"));
var _isNull2 = _interopRequireDefault(require("lodash/isNull"));
var _constants = require("./constants");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
function getPosition(level, index) {
  return `${level}-${index}`;
}
function isValid(val) {
  return !(0, _isNull2.default)(val) && !(0, _isUndefined2.default)(val);
}
function normalizedArr(val) {
  if (!Array.isArray(val)) {
    return [val];
  } else {
    return val;
  }
}
/**
 * @returns whether option includes sugInput.
 * When filterTreeNode is a function,returns the result of filterTreeNode which called with (sugInput, target, option).
 */
function filter(sugInput, option, filterTreeNode, filteredPath) {
  if (!filterTreeNode) {
    return true;
  }
  let filterFn = filterTreeNode;
  let target;
  if (typeof filterTreeNode === 'boolean') {
    filterFn = (targetVal, val) => {
      const input = targetVal.toLowerCase();
      return val.toLowerCase().includes(input);
    };
    // 当 filterTreeNode 是 bool 类型时，由 Cascader 内部判断是否符合筛选条件，使用 join('') 修复搜索英文逗号导致所有数据被匹配问题
    // When the type of of filterTreeNode is bool, Cascader internally determines whether it meets the filtering conditions.
    // Use join('') to fix the problem that searching for English commas causes all data to be matched.
    target = filteredPath.join('');
  } else {
    // 当 filterTreeNode 为函数类型时，由用户判断是否符合筛选条件，使用 join(), 和原来保持一致
    // When the type of of filterTreeNode is function, the user determines whether it meets the filtering conditions, 
    // uses join() to be consistent with the previous version.
    target = filteredPath.join();
  }
  return filterFn(sugInput, target, option);
}
/**
 * Traverse all the data by `treeData`.
 */
function traverseDataNodes(treeNodes, callback, keyMaps) {
  const realValueName = (0, _get2.default)(keyMaps, 'value', 'value');
  const realChildrenName = (0, _get2.default)(keyMaps, 'children', 'children');
  const processNode = (node, ind, parent) => {
    const children = node ? node[realChildrenName] : treeNodes;
    let item = null;
    // Process node if is not root
    if (node) {
      const nodeValue = node[realValueName];
      const key = parent ? `${parent.key}${_constants.VALUE_SPLIT}${nodeValue}` : `${nodeValue}`;
      const pos = parent ? getPosition(parent.pos, ind) : `${ind}`;
      // Map original fields to standard field names if keyMaps is provided
      const mappedData = Object.assign({}, node);
      if (keyMaps) {
        Object.entries(keyMaps).forEach(_ref => {
          let [standardKey, originalKey] = _ref;
          const value = node[originalKey];
          if (!(0, _isUndefined2.default)(value)) {
            mappedData[standardKey] = value;
          }
        });
      }
      item = {
        data: mappedData,
        ind,
        key,
        pos,
        level: parent ? parent.level + 1 : 0,
        parentKey: parent ? parent.key : null,
        path: parent ? [...parent.path, key] : [key],
        valuePath: parent ? [...parent.valuePath, nodeValue] : [nodeValue]
      };
      callback(item);
    }
    // Process children node
    if (children) {
      children.forEach((subNode, subIndex) => {
        processNode(subNode, subIndex, item);
      });
    }
  };
  processNode(null);
}
function getKeysByValuePath(valuePath) {
  if (valuePath === null || valuePath === void 0 ? void 0 : valuePath.length) {
    if (Array.isArray(valuePath[0])) {
      return valuePath.map(item => getKeyByValuePath(item));
    } else {
      return [getKeyByValuePath(valuePath)];
    }
  }
  return [];
}
function getKeyByValuePath(valuePath) {
  return valuePath.join(_constants.VALUE_SPLIT);
}
function getValuePathByKey(key) {
  return key.split(_constants.VALUE_SPLIT);
}
function getKeyByPos(pos, treeData, keyMaps) {
  const realValueName = (0, _get2.default)(keyMaps, 'value', 'value');
  const realChildrenName = (0, _get2.default)(keyMaps, 'children', 'children');
  const posArr = pos.split('-').map(item => Number(item));
  let resultData = treeData;
  const valuePath = [];
  posArr.forEach((item, index) => {
    var _a;
    resultData = index === 0 ? resultData[item] : (_a = resultData === null || resultData === void 0 ? void 0 : resultData[realChildrenName]) === null || _a === void 0 ? void 0 : _a[item];
    valuePath.push(resultData === null || resultData === void 0 ? void 0 : resultData[realValueName]);
  });
  return getKeyByValuePath(valuePath);
}
function convertDataToEntities(dataNodes, keyMaps) {
  const keyEntities = {};
  traverseDataNodes(dataNodes, data => {
    const {
      key,
      parentKey
    } = data;
    const entity = Object.assign({}, data);
    keyEntities[key] = entity;
    // Fill children
    entity.parent = keyEntities[parentKey];
    if (entity.parent) {
      entity.parent.children = entity.parent.children || [];
      entity.parent.children.push(entity);
    }
  }, keyMaps);
  return keyEntities;
}
/**
 * Get the value from data item using keyMaps mapping.
 * Similar to Tree/TreeSelect's getValueOrKey.
 * When keyMaps maps value to a custom field (e.g., 'id'), use that field;
 * otherwise fall back to 'value'.
 */
function getValueOrKey(data, keyMaps) {
  const valueName = (0, _get2.default)(keyMaps, 'value', 'value');
  if (Array.isArray(data)) {
    return data.map(item => (0, _get2.default)(item, valueName));
  }
  return (0, _get2.default)(data, valueName);
}
function calcMergeType(autoMergeValue, leafOnly) {
  let mergeType;
  if (leafOnly) {
    mergeType = _constants.strings.LEAF_ONLY_MERGE_TYPE;
  } else if (autoMergeValue) {
    mergeType = _constants.strings.AUTO_MERGE_VALUE_MERGE_TYPE;
  } else {
    mergeType = _constants.strings.NONE_MERGE_TYPE;
  }
  return mergeType;
}