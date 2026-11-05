import React, { Component } from 'react';
import type { FormUpdaterContextType } from '@douyinfe/semi-foundation/lib/cjs/form/interface';
export interface ArrayFieldProps {
    initValue?: any[];
    field?: string;
    children?: (props: ArrayFieldChildrenProps) => React.ReactNode;
}
export interface ArrayFieldChildrenProps {
    arrayFields: {
        key: string;
        field: string;
        remove: () => void;
    }[];
    add: (index?: number) => void;
    addWithInitValue: (lineObject: Record<string, any>, index?: number) => void;
}
export interface ArrayFieldState {
    keys: string[];
}
declare class ArrayFieldComponent extends Component<ArrayFieldProps, ArrayFieldState> {
    static contextType: React.Context<FormUpdaterContextType>;
    cacheFieldValues: any[];
    shouldUseInitValue: boolean;
    cacheUpdateKey: string | number;
    context: FormUpdaterContextType;
    constructor(props: ArrayFieldProps, context: FormUpdaterContextType);
    componentWillUnmount(): void;
    componentDidUpdate(): void;
    add(index?: number): string;
    addWithInitValue(rowVal: Record<string, any> | string, index?: number): void;
    remove(i: number): void;
    render(): React.JSX.Element;
}
export default ArrayFieldComponent;
