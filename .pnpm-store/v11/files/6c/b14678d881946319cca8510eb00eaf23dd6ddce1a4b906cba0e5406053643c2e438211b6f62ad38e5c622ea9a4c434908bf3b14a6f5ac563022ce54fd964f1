export interface KeyMapProps {
    value?: string;
    label?: string;
    disabled?: string;
    children?: string;
    isLeaf?: string;
}
export declare function isValid(val: any): boolean;
export declare function normalizedArr(val: any): any[];
/**
 * @returns whether option includes sugInput.
 * When filterTreeNode is a function,returns the result of filterTreeNode which called with (sugInput, target, option).
 */
export declare function filter(sugInput: string, option: any, filterTreeNode: any, filteredPath?: string[]): any;
export declare function getKeysByValuePath(valuePath: (string | number)[][] | (string | number)[]): string[];
export declare function getKeyByValuePath(valuePath: (string | number)[]): string;
export declare function getValuePathByKey(key: string): string[];
export declare function getKeyByPos(pos: string, treeData: any, keyMaps?: KeyMapProps): string;
export declare function convertDataToEntities(dataNodes: any, keyMaps?: KeyMapProps): any;
/**
 * Get the value from data item using keyMaps mapping.
 * Similar to Tree/TreeSelect's getValueOrKey.
 * When keyMaps maps value to a custom field (e.g., 'id'), use that field;
 * otherwise fall back to 'value'.
 */
export declare function getValueOrKey(data: any, keyMaps?: KeyMapProps): any;
export declare function calcMergeType(autoMergeValue: boolean, leafOnly: boolean): string;
