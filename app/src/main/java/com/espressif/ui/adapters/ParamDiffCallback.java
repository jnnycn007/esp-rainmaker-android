// Copyright 2022 Espressif Systems (Shanghai) PTE LTD
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.espressif.ui.adapters;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;

import com.espressif.ui.models.Param;

import java.util.List;

public class ParamDiffCallback extends DiffUtil.Callback {

    private final List<Param> oldParamList;
    private final List<Param> newParamList;

    public ParamDiffCallback(List<Param> oldParamList, List<Param> newParamList) {
        this.oldParamList = oldParamList;
        this.newParamList = newParamList;
    }

    private static boolean isBoolean(Param param) {
        String dataType = param.getDataType();
        return dataType != null
                && (dataType.equalsIgnoreCase("bool") || dataType.equalsIgnoreCase("boolean"));
    }

    @Override
    public int getOldListSize() {
        return oldParamList.size();
    }

    @Override
    public int getNewListSize() {
        return newParamList.size();
    }

    @Override
    public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
        boolean areItemsTheSame = oldParamList.get(oldItemPosition).getName().equals(newParamList.get(newItemPosition).getName());
        return areItemsTheSame;
    }

    @Override
    public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
        final Param oldParam = oldParamList.get(oldItemPosition);
        final Param newParam = newParamList.get(newItemPosition);

        // A boolean param's bound View can be out of step with this baseline: interaction
        // handlers mutate the adapter's list in place (ParamAdapter sets switchStatus on
        // write success) without a rebind, so a later refresh can find "no change" while
        // the switch on screen still shows the old state, and never correct itself.
        // Rebinding these unconditionally is cheap - the listener is detached before
        // setChecked - and leaves slider diffing untouched so drags are not disturbed.
        if (isBoolean(oldParam) || isBoolean(newParam)) {
            return false;
        }

        int a = oldParam.compareTo(newParam);

        if (oldParam.getDependencies() != null || newParam.getDependencies() != null) {
            return false;
        }

        if (a == 0) {
            return true;
        } else {
            return false;
        }
    }

    @Nullable
    @Override
    public Object getChangePayload(int oldItemPosition, int newItemPosition) {
        // Implement method if you're going to use ItemAnimator
        return super.getChangePayload(oldItemPosition, newItemPosition);
    }
}
