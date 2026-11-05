import { BaseFormApi, FormState } from '@douyinfe/semi-foundation/lib/cjs/form/interface';
interface ProxyInternals<T extends Record<string, any> = any> {
    __realApi: BaseFormApi<T> | null;
    __bind: ((api: BaseFormApi<T>) => void) | null;
    __unbind: (() => void) | null;
    __updateState: ((state: FormState<T>) => void) | null;
}
/**
 * useForm - create a FormApi usable outside of Form component
 *
 * Usage:
 *   const [formApi, formState, formValues] = Form.useForm();
 *   <Form form={formApi}>...</Form>
 *
 * @returns [formApi, formState, values]
 */
declare function useForm<T extends Record<string, any> = any>(): readonly [BaseFormApi<T> & ProxyInternals<T>, FormState<T>, T];
export default useForm;
