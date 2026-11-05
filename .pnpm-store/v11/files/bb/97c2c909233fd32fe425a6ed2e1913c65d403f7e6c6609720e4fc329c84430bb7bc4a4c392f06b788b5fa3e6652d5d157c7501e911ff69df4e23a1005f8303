import React, { ReactNode } from 'react';
import PropTypes from 'prop-types';
import '@douyinfe/semi-foundation/lib/es/tag/tag.css';
import BaseComponent, { BaseProps } from '../_base/baseComponent';
export interface SplitTagGroupProps extends BaseProps {
    'aria-label'?: React.AriaAttributes['aria-label'];
}
/**
 * SplitTagGroup wraps a list of `Tag` siblings and renders them as a single
 * connected group: the first child gets rounded corners on the leading edge,
 * the last child on the trailing edge, and inner children get zero radius.
 *
 * The class injection happens at render time via `React.Children.map` +
 * `cloneElement`, so we never reach into the DOM and can react to children
 * changes synchronously without a `MutationObserver`.
 */
export default class SplitTagGroup extends BaseComponent<SplitTagGroupProps> {
    static propTypes: {
        style: PropTypes.Requireable<object>;
        className: PropTypes.Requireable<string>;
        children: PropTypes.Requireable<PropTypes.ReactNodeLike>;
        'aria-label': PropTypes.Requireable<string>;
    };
    static defaultProps: {};
    /**
     * Walk only the *direct* children, skipping non-elements (text / nullish),
     * and inject `semi-tag-first` / `semi-tag-last` based on element index
     * within the visible-element list. Existing className on the child is
     * preserved.
     */
    decorateChildren: (children: ReactNode) => ReactNode;
    render(): React.JSX.Element;
}
