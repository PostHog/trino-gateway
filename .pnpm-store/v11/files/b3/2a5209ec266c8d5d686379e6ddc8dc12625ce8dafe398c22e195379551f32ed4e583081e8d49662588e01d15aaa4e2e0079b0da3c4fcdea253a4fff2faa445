import React from 'react';
import PropTypes from 'prop-types';
import { ContextValue } from './context';
import { ResponsiveMap, BreakpointScreens } from './responsiveTypes';
export interface ConfigProviderProps extends Omit<ContextValue, 'onBreakpoint' | 'screens'> {
    /**
     * Custom responsive map configuration
     * If not provided, default responsive map will be used
     */
    responsiveMap?: ResponsiveMap;
    /**
     * Enable responsive observing in ConfigProvider.
     *
     * - When false (default): ConfigProvider will not register any matchMedia listeners.
     * - When true: listeners will be registered lazily on first subscription.
     */
    responsiveObserve?: boolean;
}
interface ConfigProviderState {
    screens: BreakpointScreens;
}
/**
 * Default responsive map configuration
 * Can be accessed via ConfigProvider.defaultResponsiveMap
 */
export declare const defaultResponsiveMap: ResponsiveMap;
export declare const ConfigConsumer: React.Consumer<ContextValue>;
export default class ConfigProvider extends React.Component<ConfigProviderProps, ConfigProviderState> {
    static propTypes: {
        locale: PropTypes.Requireable<object>;
        timeZone: PropTypes.Requireable<NonNullable<string | number>>;
        getPopupContainer: PropTypes.Requireable<(...args: any[]) => any>;
        direction: PropTypes.Requireable<string>;
        responsiveMap: PropTypes.Requireable<object>;
        responsiveObserve: PropTypes.Requireable<boolean>;
    };
    static defaultProps: {
        locale: import("../locale/interface").Locale;
        direction: string;
        responsiveObserve: boolean;
    };
    /**
     * Default responsive map - static property for backward compatibility
     */
    static defaultResponsiveMap: ResponsiveMap;
    private unRegisters;
    private hasRegisteredMediaQueries;
    private hasWarnedResponsiveObserve;
    private screensListeners;
    private changeListeners;
    /**
     * Synchronous source of truth for current breakpoint matches.
     * `setState` is async, so reading `this.state.screens` immediately after
     * registering listeners returns stale values. Subscriber callbacks read
     * from this ref to always get the freshest snapshot.
     */
    private currentScreensRef;
    constructor(props: ConfigProviderProps);
    componentDidMount(): void;
    componentDidUpdate(prevProps: ConfigProviderProps): void;
    componentWillUnmount(): void;
    private ensureMediaQueriesRegistered;
    /**
     * Register media query listeners.
     *
     * To avoid stale-state bug in immediate subscriber callbacks, we
     * synchronously read all `matchMedia(...).matches` once *before* attaching
     * the change listeners, write the result to both `currentScreensRef` and
     * `state.screens` in a single batched setState, and only then start
     * tracking changes (with `callInInit: false` so we don't double-fire).
     */
    private registerMediaQueries;
    /**
     * Unregister all media query listeners
     */
    private unregisterMediaQueries;
    /**
     * Update screen state and notify listeners
     */
    private updateScreen;
    /**
     * Notify all registered listeners
     */
    private notifyListeners;
    /**
     * Subscribe to breakpoint changes
     * @param callback Function to call when breakpoint changes
     * @returns Unsubscribe function
     */
    private handleBreakpoint;
    renderChildren(): string | number | boolean | Iterable<React.ReactNode> | React.JSX.Element;
    render(): React.JSX.Element;
}
export type { ResponsiveMap, BreakpointScreens, Breakpoint, OnBreakpointScreensCallback, OnBreakpointChangeCallback, } from './responsiveTypes';
