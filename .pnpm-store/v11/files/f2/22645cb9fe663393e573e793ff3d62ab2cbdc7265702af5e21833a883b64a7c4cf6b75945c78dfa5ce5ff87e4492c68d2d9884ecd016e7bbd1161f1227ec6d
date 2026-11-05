import React, { Children, cloneElement, isValidElement } from 'react';
import PropTypes from 'prop-types';
import classNames from 'classnames';
import { cssClasses } from '@douyinfe/semi-foundation/lib/es/tag/constants';
import '@douyinfe/semi-foundation/lib/es/tag/tag.css';
import BaseComponent from '../_base/baseComponent';
const prefixCls = cssClasses.PREFIX;
/**
 * SplitTagGroup wraps a list of `Tag` siblings and renders them as a single
 * connected group: the first child gets rounded corners on the leading edge,
 * the last child on the trailing edge, and inner children get zero radius.
 *
 * The class injection happens at render time via `React.Children.map` +
 * `cloneElement`, so we never reach into the DOM and can react to children
 * changes synchronously without a `MutationObserver`.
 */
export default class SplitTagGroup extends BaseComponent {
  constructor() {
    super(...arguments);
    /**
     * Walk only the *direct* children, skipping non-elements (text / nullish),
     * and inject `semi-tag-first` / `semi-tag-last` based on element index
     * within the visible-element list. Existing className on the child is
     * preserved.
     */
    this.decorateChildren = children => {
      const validChildren = Children.toArray(children).filter(child => /*#__PURE__*/isValidElement(child));
      const lastIndex = validChildren.length - 1;
      let visibleIndex = -1;
      return Children.map(children, child => {
        var _a;
        if (! /*#__PURE__*/isValidElement(child)) {
          return child;
        }
        visibleIndex += 1;
        const extraCls = classNames((_a = child.props) === null || _a === void 0 ? void 0 : _a.className, {
          [`${prefixCls}-first`]: visibleIndex === 0,
          [`${prefixCls}-last`]: visibleIndex === lastIndex
        });
        return /*#__PURE__*/cloneElement(child, {
          className: extraCls
        });
      });
    };
  }
  render() {
    const {
      children,
      style,
      className
    } = this.props;
    const cls = classNames(`${prefixCls}-split`, className);
    return /*#__PURE__*/React.createElement("div", {
      className: cls,
      style: style,
      role: "group",
      "aria-label": this.props['aria-label']
    }, this.decorateChildren(children));
  }
}
SplitTagGroup.propTypes = {
  style: PropTypes.object,
  className: PropTypes.string,
  children: PropTypes.node,
  'aria-label': PropTypes.string
};
SplitTagGroup.defaultProps = {};