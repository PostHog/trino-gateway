import React, { useMemo } from 'react';
import cls from 'classnames';
import MarkdownRender from '../../markdownRender';
import { cssClasses, strings } from '@douyinfe/semi-foundation/lib/es/chat/constants';
import { FileAttachment, ImageAttachment } from '../attachment';
import Code from './code';
import { escapeHtmlInMarkdown } from '@douyinfe/semi-foundation/lib/es/utils/escapeHtml';
const {
  PREFIX_CHAT_BOX
} = cssClasses;
const {
  MESSAGE_STATUS,
  MODE,
  ROLE
} = strings;
const ChatBoxContent = props => {
  const {
    message = {},
    customRenderFunc,
    role: roleInfo,
    customMarkDownComponents,
    mode,
    markdownRenderProps,
    escapeHtml
  } = props;
  const {
    content,
    role,
    status
  } = message;
  const shouldEscapeHtml = escapeHtml && role === ROLE.USER;
  const markdownComponents = useMemo(() => Object.assign({
    'code': Code,
    'SemiFile': FileAttachment,
    'img': ImageAttachment
  }, customMarkDownComponents), [customMarkDownComponents]);
  const wrapCls = useMemo(() => {
    const isUser = role === ROLE.USER;
    const bubble = mode === MODE.BUBBLE;
    const userBubble = mode === MODE.USER_BUBBLE && isUser;
    return cls(`${PREFIX_CHAT_BOX}-content`, {
      [`${PREFIX_CHAT_BOX}-content-${mode}`]: bubble || userBubble,
      [`${PREFIX_CHAT_BOX}-content-user`]: bubble && isUser || userBubble,
      [`${PREFIX_CHAT_BOX}-content-error`]: status === MESSAGE_STATUS.ERROR && (bubble || userBubble)
    });
  }, [role, status, mode]);
  const node = useMemo(() => {
    if (status === MESSAGE_STATUS.LOADING) {
      return /*#__PURE__*/React.createElement("span", {
        className: `${PREFIX_CHAT_BOX}-content-loading`
      }, /*#__PURE__*/React.createElement("span", {
        className: `${PREFIX_CHAT_BOX}-content-loading-item`
      }));
    } else {
      let realContent;
      if (typeof content === 'string') {
        const rawText = shouldEscapeHtml ? escapeHtmlInMarkdown(content) : content;
        realContent = /*#__PURE__*/React.createElement(MarkdownRender, Object.assign({
          format: 'md',
          raw: rawText,
          components: markdownComponents
        }, markdownRenderProps));
      } else if (Array.isArray(content)) {
        realContent = content.map((item, index) => {
          var _a;
          if (item.type === 'text') {
            const rawText = shouldEscapeHtml ? escapeHtmlInMarkdown(item.text) : item.text;
            return /*#__PURE__*/React.createElement(MarkdownRender, Object.assign({
              key: `index`,
              format: 'md',
              raw: rawText,
              components: markdownComponents
            }, markdownRenderProps));
          } else if (item.type === 'image_url') {
            return /*#__PURE__*/React.createElement(ImageAttachment, {
              key: `index`,
              src: item.image_url.url
            });
          } else if (item.type === 'file_url') {
            const {
              name,
              size,
              url,
              type
            } = item.file_url;
            const realType = (_a = name.split('.').pop()) !== null && _a !== void 0 ? _a : type === null || type === void 0 ? void 0 : type.split('/').pop();
            return /*#__PURE__*/React.createElement(FileAttachment, {
              key: `index`,
              url: url,
              name: name,
              size: size,
              type: realType
            });
          }
          return null;
        });
      }
      return /*#__PURE__*/React.createElement(React.Fragment, null, realContent);
    }
  }, [status, content]);
  if (customRenderFunc) {
    return customRenderFunc({
      message,
      role: roleInfo,
      defaultContent: node,
      className: wrapCls
    });
  } else {
    return /*#__PURE__*/React.createElement("div", {
      className: wrapCls
    }, node);
  }
};
export default ChatBoxContent;