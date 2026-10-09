'use strict';

/**
 * File text-content viewer controller.
 * Fetches /api/file/{id}/data?size=content (OCR text) and displays it
 * with a tiled watermark overlay, so the text can't be quietly
 * screenshotted without the user identity and timestamp baked in.
 */
angular.module('docs').controller('FileContent', function ($rootScope, $scope, $stateParams, $http) {
  $scope.loading = true;
  $scope.error = null;
  $scope.text = '';

  // 拉取 OCR 文本。后端返回 text/plain，用 transformResponse 包成字符串。
  $http({
    url: '../api/file/' + $stateParams.fileId + '/data',
    method: 'GET',
    params: { size: 'content' },
    transformResponse: [function (data) { return data; }]
  }).then(function (resp) {
    $scope.text = resp.data || '';
    $scope.loading = false;
    // 文本就绪后再注入水印（避免页面高度还没确定时空铺）
    injectWatermark();
  }, function (err) {
    $scope.error = err.status === 403 ? 'forbidden' : 'load_error';
    $scope.loading = false;
  });

  /**
   * 注入 600 个水印 div 到 .watermark-overlay，覆盖整个 .text-content 区域。
   * 与 FileModalView 用同一套逻辑（20 列 × 30 行，280×120 网格，旋转 -30°）。
   */
  var injectWatermark = function () {
    var overlay = document.querySelector('.watermark-overlay');
    if (!overlay) {
      return setTimeout(injectWatermark, 200);
    }
    var now = new Date();
    var pad = function (n) { return n < 10 ? '0' + n : '' + n; };
    var wmTime = now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate()) +
                 ' ' + pad(now.getHours()) + ':' + pad(now.getMinutes()) + ':' + pad(now.getSeconds());
    var wmUser = ($rootScope.userInfo && $rootScope.userInfo.username) || 'anonymous';
    var wmText = wmUser + ' · ' + wmTime;

    overlay.innerHTML = '';
    for (var row = 0; row < 30; row++) {
      for (var col = 0; col < 20; col++) {
        var line = document.createElement('div');
        line.textContent = wmText;
        line.style.position = 'absolute';
        line.style.left = (col * 280) + 'px';
        line.style.top = (row * 120 - 200) + 'px';
        line.style.transform = 'rotate(-30deg)';
        line.style.transformOrigin = '0 0';
        line.style.whiteSpace = 'nowrap';
        line.style.color = '#000';
        line.style.opacity = '0.18';
        line.style.fontSize = '22px';
        line.style.fontWeight = '600';
        line.style.fontFamily = 'Helvetica, Arial, sans-serif';
        line.style.pointerEvents = 'none';
        overlay.appendChild(line);
      }
    }
  };
});
