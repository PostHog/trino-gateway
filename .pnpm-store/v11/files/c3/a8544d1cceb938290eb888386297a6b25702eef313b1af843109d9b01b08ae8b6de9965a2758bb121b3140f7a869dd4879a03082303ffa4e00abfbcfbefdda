import React from 'react';
import { render as reactRender, unmount as reactUnmount } from '../_utils/reactRender';
import cls from 'classnames';
import PropTypes from 'prop-types';
import ConfigContext from '../configProvider/context';
import NotificationListFoundation from '@douyinfe/semi-foundation/lib/es/notification/notificationListFoundation';
import { cssClasses, strings } from '@douyinfe/semi-foundation/lib/es/notification/constants';
import Notice from './notice';
import BaseComponent from '../_base/baseComponent';
import '@douyinfe/semi-foundation/lib/es/notification/notification.css';
import getUuid from '@douyinfe/semi-foundation/lib/es/utils/uuid';
import useNotification from './useNotification';
import CSSAnimation from "../_cssAnimation";
import semiGlobal from '../_utils/semi-global';
let ref = null;
const defaultConfig = {
  duration: 3,
  position: 'topRight',
  motion: true,
  content: '',
  title: '',
  zIndex: 1010
};
class NotificationList extends BaseComponent {
  constructor(props) {
    var _this;
    super(props);
    _this = this;
    this.add = noticeOpts => this.foundation.addNotice(noticeOpts);
    this.has = id => this.foundation.has(id);
    this.remove = id => {
      this.foundation.removeNotice(String(id));
    };
    this.update = (id, opts) => {
      return this.foundation.update(id, opts);
    };
    this.destroyAll = () => this.foundation.destroyAll();
    this.renderNoticeInPosition = function (notices, position) {
      let removedItems = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : [];
      let updatedItems = arguments.length > 3 && arguments[3] !== undefined ? arguments[3] : [];
      const className = cls(cssClasses.LIST);
      // TODO notifyOnClose
      if (notices.length) {
        const style = _this.setPosInStyle(notices[0]);
        return (
          /*#__PURE__*/
          // @ts-ignore
          React.createElement("div", {
            placement: position,
            key: position,
            className: className,
            style: style
          }, notices.map((notice, index) => {
            const isRemoved = removedItems.find(removedItem => removedItem.id === notice.id) !== undefined;
            return /*#__PURE__*/React.createElement(CSSAnimation, {
              key: notice.id,
              animationState: isRemoved ? "leave" : "enter",
              startClassName: `${cssClasses.NOTICE}-animation-${isRemoved ? "hide" : "show"}_${position}`
            }, _ref => {
              let {
                animationClassName,
                animationEventsNeedBind,
                isAnimating
              } = _ref;
              return isRemoved && !isAnimating ? null : /*#__PURE__*/React.createElement(Notice, Object.assign({}, notice, {
                ref: notice => {
                  if (notice && updatedItems.some(item => item.id === notice.props.id)) {
                    notice.foundation.restartCloseTimer();
                  }
                },
                className: cls({
                  [notice.className]: Boolean(notice.className),
                  [animationClassName]: true
                })
              }, animationEventsNeedBind, {
                style: Object.assign({}, notice.style),
                close: _this.remove
              }));
            });
          }))
        );
      }
      return null;
    };
    this.state = {
      notices: [],
      removedItems: [],
      updatedItems: []
    };
    this.noticeStorage = [];
    this.removeItemStorage = [];
    this.foundation = new NotificationListFoundation(this.adapter);
  }
  get adapter() {
    var _this2 = this;
    return Object.assign(Object.assign({}, super.adapter), {
      updateNotices: function (notices) {
        let removedItems = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : [];
        let updatedItems = arguments.length > 2 && arguments[2] !== undefined ? arguments[2] : [];
        _this2.noticeStorage = [...notices];
        _this2.removeItemStorage = [...removedItems];
        // setState is async sometimes and react often merges state, so use "this" , make sure other code always get right data.
        _this2.setState({
          notices,
          removedItems,
          updatedItems
        });
      },
      getNotices: () => this.noticeStorage
    });
  }
  static addNotice(notice) {
    var _a, _b, _c;
    // Get global config for Notification
    const globalConfig = ((_b = (_a = semiGlobal === null || semiGlobal === void 0 ? void 0 : semiGlobal.config) === null || _a === void 0 ? void 0 : _a.overrideDefaultProps) === null || _b === void 0 ? void 0 : _b.Notification) || {};
    // Merge configs with priority: notice > globalConfig > defaultConfig
    notice = Object.assign(Object.assign(Object.assign({}, defaultConfig), globalConfig), notice);
    const id = (_c = notice.id) !== null && _c !== void 0 ? _c : getUuid('notification');
    if (!ref) {
      const {
        getPopupContainer
      } = notice;
      const div = document.createElement('div');
      if (!this.wrapperId) {
        this.wrapperId = getUuid('notification-wrapper').slice(0, 32);
      }
      div.className = cssClasses.WRAPPER;
      div.id = this.wrapperId;
      div.style.zIndex = String(typeof notice.zIndex === 'number' ? notice.zIndex : defaultConfig.zIndex);
      if (getPopupContainer) {
        const container = getPopupContainer();
        container.appendChild(div);
      } else {
        document.body.appendChild(div);
      }
      reactRender(/*#__PURE__*/React.createElement(NotificationList, {
        ref: instance => {
          if (instance) {
            ref = instance;
            instance.add(Object.assign(Object.assign({}, notice), {
              id
            }));
          }
        }
      }), div);
    } else {
      if (ref.has(`${id}`)) {
        ref.update(id, notice);
      } else {
        ref.add(Object.assign(Object.assign({}, notice), {
          id
        }));
      }
    }
    return id;
  }
  static removeNotice(id) {
    if (ref) {
      ref.remove(id);
    }
    return id;
  }
  static info(opts) {
    var _a, _b;
    // Merge with global config
    const globalConfig = ((_b = (_a = semiGlobal === null || semiGlobal === void 0 ? void 0 : semiGlobal.config) === null || _a === void 0 ? void 0 : _a.overrideDefaultProps) === null || _b === void 0 ? void 0 : _b.Notification) || {};
    return this.addNotice(Object.assign(Object.assign(Object.assign(Object.assign({}, defaultConfig), globalConfig), opts), {
      type: 'info'
    }));
  }
  static success(opts) {
    var _a, _b;
    // Merge with global config
    const globalConfig = ((_b = (_a = semiGlobal === null || semiGlobal === void 0 ? void 0 : semiGlobal.config) === null || _a === void 0 ? void 0 : _a.overrideDefaultProps) === null || _b === void 0 ? void 0 : _b.Notification) || {};
    return this.addNotice(Object.assign(Object.assign(Object.assign(Object.assign({}, defaultConfig), globalConfig), opts), {
      type: 'success'
    }));
  }
  static error(opts) {
    var _a, _b;
    // Merge with global config
    const globalConfig = ((_b = (_a = semiGlobal === null || semiGlobal === void 0 ? void 0 : semiGlobal.config) === null || _a === void 0 ? void 0 : _a.overrideDefaultProps) === null || _b === void 0 ? void 0 : _b.Notification) || {};
    return this.addNotice(Object.assign(Object.assign(Object.assign(Object.assign({}, defaultConfig), globalConfig), opts), {
      type: 'error'
    }));
  }
  static warning(opts) {
    var _a, _b;
    // Merge with global config
    const globalConfig = ((_b = (_a = semiGlobal === null || semiGlobal === void 0 ? void 0 : semiGlobal.config) === null || _a === void 0 ? void 0 : _a.overrideDefaultProps) === null || _b === void 0 ? void 0 : _b.Notification) || {};
    return this.addNotice(Object.assign(Object.assign(Object.assign(Object.assign({}, defaultConfig), globalConfig), opts), {
      type: 'warning'
    }));
  }
  static open(opts) {
    var _a, _b;
    // Merge with global config
    const globalConfig = ((_b = (_a = semiGlobal === null || semiGlobal === void 0 ? void 0 : semiGlobal.config) === null || _a === void 0 ? void 0 : _a.overrideDefaultProps) === null || _b === void 0 ? void 0 : _b.Notification) || {};
    return this.addNotice(Object.assign(Object.assign(Object.assign(Object.assign({}, defaultConfig), globalConfig), opts), {
      type: 'default'
    }));
  }
  static close(id) {
    return this.removeNotice(id);
  }
  static destroyAll() {
    var _a;
    if (ref) {
      ref.destroyAll();
      const wrapper = document.querySelector(`#${this.wrapperId}`);
      if (wrapper) {
        reactUnmount(wrapper);
        (_a = wrapper.parentNode) === null || _a === void 0 ? void 0 : _a.removeChild(wrapper);
      }
      ref = null;
      this.wrapperId = null;
    }
  }
  static config(opts) {
    ['top', 'left', 'bottom', 'right'].map(pos => {
      if (pos in opts) {
        defaultConfig[pos] = opts[pos];
      }
    });
    if (typeof opts.zIndex === 'number') {
      defaultConfig.zIndex = opts.zIndex;
    }
    if (typeof opts.duration === 'number') {
      defaultConfig.duration = opts.duration;
    }
    if (typeof opts.position === 'string') {
      defaultConfig.position = opts.position;
    }
  }
  setPosInStyle(noticeInstance) {
    const style = {};
    ['top', 'left', 'bottom', 'right'].forEach(pos => {
      if (pos in noticeInstance) {
        const val = noticeInstance[pos];
        style[pos] = typeof val === 'number' ? `${val}px` : val;
      }
    });
    return style;
  }
  render() {
    let {
      notices
    } = this.state;
    const {
      removedItems,
      updatedItems
    } = this.state;
    notices = Array.from(new Set([...notices, ...removedItems]));
    const noticesInPosition = {
      top: [],
      topLeft: [],
      topRight: [],
      bottom: [],
      bottomLeft: [],
      bottomRight: []
    };
    notices.forEach(notice => {
      const direction = notice.direction || this.context.direction;
      const defaultPosition = direction === 'rtl' ? 'topLeft' : 'topRight';
      const position = notice.position || defaultPosition;
      noticesInPosition[position].push(notice);
    });
    const noticesList = Object.entries(noticesInPosition).map(obj => {
      const pos = obj[0];
      const noticesInPos = obj[1];
      return this.renderNoticeInPosition(noticesInPos, pos, removedItems, updatedItems);
    });
    return /*#__PURE__*/React.createElement(React.Fragment, null, noticesList);
  }
}
NotificationList.contextType = ConfigContext;
NotificationList.propTypes = {
  style: PropTypes.object,
  className: PropTypes.string,
  direction: PropTypes.oneOf(strings.directions)
};
NotificationList.defaultProps = {};
NotificationList.useNotification = useNotification;
export default NotificationList;