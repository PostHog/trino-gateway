import React from 'react';
import type { WithFieldOption } from '@douyinfe/semi-foundation/lib/es/form/interface';
import type { CommonFieldProps, CommonexcludeType } from '../interface';
/**
 * withFiled is used to inject components
 * 1. Takes over the value and onChange of the component and synchronizes them to Form Foundation
 * 2. Insert <Label>
 * 3. Insert <ErrorMessage>
 */
declare function withField<C extends React.ElementType, T extends Omit<React.ComponentProps<C>, keyof CommonexcludeType> & CommonFieldProps & React.RefAttributes<any>, R extends React.ComponentType<T>>(Component: C, opts?: WithFieldOption): R;
export default withField;
