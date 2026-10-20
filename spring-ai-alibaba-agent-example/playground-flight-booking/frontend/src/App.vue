<script setup lang="ts">
import enUS from "ant-design-vue/es/locale/en_US";
import zhCN from "ant-design-vue/es/locale/zh_CN";
import dayjs from "dayjs";
import { PRIMARY_COLOR } from "@/base/constants";
import Portal from "./Portal.vue";
dayjs.locale("en");
</script>

<template>
  <a-config-provider
    :locale="zhCN"
    :theme="{
      token: {
        colorPrimary: PRIMARY_COLOR,
      },
    }"
  >
    <Portal />
  </a-config-provider>
</template>

<style lang="less"></style>
