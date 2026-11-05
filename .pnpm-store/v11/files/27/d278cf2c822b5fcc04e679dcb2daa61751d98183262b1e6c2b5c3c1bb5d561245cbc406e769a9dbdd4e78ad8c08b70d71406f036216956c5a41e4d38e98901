import React from 'react';
import type { BaseProps } from '../_base/baseComponent';
export type Type = 'primary' | 'secondary' | 'tertiary' | 'warning' | 'danger';
export interface DropdownItemProps extends BaseProps {
    disabled?: boolean;
    selected?: boolean;
    onClick?: React.MouseEventHandler<HTMLLIElement>;
    onMouseEnter?: React.MouseEventHandler<HTMLLIElement>;
    onMouseLeave?: React.MouseEventHandler<HTMLLIElement>;
    onContextMenu?: React.MouseEventHandler<HTMLLIElement>;
    forwardRef?: (ele: HTMLLIElement) => void;
    type?: Type;
    active?: boolean;
    icon?: React.ReactNode;
    onKeyDown?: (e: React.KeyboardEvent) => void;
    showTick?: boolean;
    /** internal prop, please do not use  */
    hover?: boolean;
}
declare const DropdownItem: React.ForwardRefExoticComponent<DropdownItemProps & React.RefAttributes<HTMLLIElement>> & {
    elementType?: string;
};
export default DropdownItem;
