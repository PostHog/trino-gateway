import _get from "lodash/get";
import _isUndefined from "lodash/isUndefined";
import _isNull from "lodash/isNull";
import { strings, VALUE_SPLIT } from './constants';
function getPosition(level, index) {
  return `${level}-${index}`;
}
export function isValid(val) {
  return !_isNull(val) && !_isUndefined(val);
}
export function normalizedArr(val) {
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
export function filter(sugInput, option, filterTreeNode, filteredPath) {
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
  const realValueName = _get(keyMaps, 'value', 'value');
  const realChildrenName = _get(keyMaps, 'children', 'children');
  const processNode = (node, ind, parent) => {
    const children = node ? node[realChildrenName] : treeNodes;
    let item = null;
    // Process node if is not root
    if (node) {
      const nodeValue = node[realValueName];
      const key = parent ? `${parent.key}${VALUE_SPLIT}${nodeValue}` : `${nodeValue}`;
      const pos = parent ? getPosition(parent.pos, ind) : `${ind}`;
      // Map original fields to standard field names if keyMaps is provided
      const mappedData = Object.assign({}, node);
      if (keyMaps) {
        Object.entries(keyMaps).forEach(_ref => {
          let [standardKey, originalKey] = _ref;
          const value = node[originalKey];
          if (!_isUndefined(value)) {
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
export function getKeysByValuePath(valuePath) {
  if (valuePath === null || valuePath === void 0 ? void 0 : valuePath.length) {
    if (Array.isArray(valuePath[0])) {
      return valuePath.map(item => getKeyByValuePath(item));
    } else {
      return [getKeyByValuePath(valuePath)];
    }
  }
  return [];
}
export function getKeyByValuePath(valuePath) {
  return valuePath.join(VALUE_SPLIT);
}
export function getValuePathByKey(key) {
  return key.split(VALUE_SPLIT);
}
export function getKeyByPos(pos, treeData, keyMaps) {
  const realValueName = _get(keyMaps, 'value', 'value');
  const realChildrenName = _get(keyMaps, 'children', 'children');
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
export function convertDataToEntities(dataNodes, keyMaps) {
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
export function getValueOrKey(data, keyMaps) {
  const valueName = _get(keyMaps, 'value', 'value');
  if (Array.isArray(data)) {
    return data.map(item => _get(item, valueName));
  }
  return _get(data, valueName);
}
export function calcMergeType(autoMergeValue, leafOnly) {
  let mergeType;
  if (leafOnly) {
    mergeType = strings.LEAF_ONLY_MERGE_TYPE;
  } else if (autoMergeValue) {
    mergeType = strings.AUTO_MERGE_VALUE_MERGE_TYPE;
  } else {
    mergeType = strings.NONE_MERGE_TYPE;
  }
  return mergeType;
}