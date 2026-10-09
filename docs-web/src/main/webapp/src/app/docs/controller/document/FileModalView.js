'use strict';

/**
 * File modal view controller.
 */
angular.module('docs').controller('FileModalView', function ($rootScope, $uibModalInstance, $scope, $state, $stateParams, $sce, Restangular, $transitions) {
  // 在打开文件时计算一次时间戳。
  // 之前模板里写的是 {{ 'now' | date:'...' }}，每次 digest 都会重算导致时间不断滚动。
  var now = new Date();
  var pad = function (n) { return n < 10 ? '0' + n : '' + n; };
  var watermarkTime =
    now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate()) +
    ' ' + pad(now.getHours()) + ':' + pad(now.getMinutes()) + ':' + pad(now.getSeconds());
  var navUser = $rootScope && $rootScope.userInfo;
  var watermarkUser = (navUser && navUser.username) ? navUser.username : 'anonymous';
  var watermarkText = watermarkUser + ' · ' + watermarkTime;

  // 用真实 DOM 元素重复铺水印：之前用 background-image + base64 SVG 在 chrome 整页渲染时
  // 会被 PDF iframe 遮住；改用 JS 直接 appendChild 真实 div，每个 div 旋转 -30° 包含
  // "username · timestamp"，铺满整个 .watermark-overlay 容器。
  // 这样：
  //  1. 不依赖 SVG dataURI 解析（chrome 偶发拒绝渲染 background-image）
  //  2. 真实 DOM 元素，z-index 不会跟 iframe 冲突
  //  3. 不受缓存模板里 background-image 没渲染的影响
  var injectWatermarkLines = function () {
    var overlay = document.querySelector('.watermark-overlay');
    if (!overlay) {
      // 模板可能没渲染好（缓存旧模板 / angular 异步），0.5s 后再试一次
      return setTimeout(injectWatermarkLines, 500);
    }
    overlay.innerHTML = '';
    // 网格：列间距 280px，行间距 120px，按对角线 -30° 排
    // 20 列 × 30 行 = 600 个水印，确保铺满 1440px 高的 PDF
    for (var row = 0; row < 30; row++) {
      for (var col = 0; col < 20; col++) {
        var line = document.createElement('div');
        line.textContent = watermarkText;
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
  // $rootScope 上保留 watermarkText 供 Navigation 兜底用
  $rootScope.watermarkText = watermarkText;
  // 等 DOM 渲染完（modal 打开是异步的）后注入水印
  setTimeout(injectWatermarkLines, 0);
  // 兜底：用户如果重新进入文件预览，再注入一次
  $transitions.onStart({}, function () { setTimeout(injectWatermarkLines, 100); });

  var setFile = function (files) {
    // Search current file
    _.each(files, function (value) {
      if (value.id === $stateParams.fileId) {
        $scope.file = value;
        $scope.trustedFileUrl = $sce.trustAsResourceUrl('../api/file/' + $stateParams.fileId + '/data');
      }
    });
  };

  // Load files
  Restangular.one('file/list').get({ id: $stateParams.id }).then(function (data) {
    $scope.files = data.files;
    setFile(data.files);

    // File not found, maybe it's a version
    if (!$scope.file) {
      Restangular.one('file/' + $stateParams.fileId + '/versions').get().then(function (data) {
        setFile(data.files);
      });
    }
  });

  /**
   * Return the next file.
   */
  $scope.nextFile = function () {
    var next = undefined;
    _.each($scope.files, function (value, key) {
      if (value.id === $stateParams.fileId) {
        next = $scope.files[key + 1];
      }
    });
    return next;
  };

  /**
   * Return the previous file.
   */
  $scope.previousFile = function () {
    var previous = undefined;
    _.each($scope.files, function (value, key) {
      if (value.id === $stateParams.fileId) {
        previous = $scope.files[key - 1];
      }
    });
    return previous;
  };

  /**
   * Navigate to the next file.
   */
  $scope.goNextFile = function () {
    var next = $scope.nextFile();
    if (next) {
      $state.go('^.file', { id: $stateParams.id, fileId: next.id });
    }
  };

  /**
   * Navigate to the previous file.
   */
  $scope.goPreviousFile = function () {
    var previous = $scope.previousFile();
    if (previous) {
      $state.go('^.file', { id: $stateParams.id, fileId: previous.id });
    }
  };

  /**
   * Open the file in a new window.
   */
  $scope.openFile = function () {
    window.open('../api/file/' + $stateParams.fileId + '/data');
  };

  /**
   * Open the file content a new window.
   */
  $scope.openFileContent = function () {
    // 直接打开后端 /api/file/.../data?size=content。
    // 后端检测 Accept 头：浏览器（text/html）会自动返回带水印的 HTML 页面；
    // API 调用方仍可拿到裸 text/plain（保持兼容）。
    window.open('../api/file/' + $stateParams.fileId + '/data?size=content', '_blank');
  };

  /**
   * Print the file.
   */
  $scope.printFile = function () {
    var popup = window.open('../api/file/' + $stateParams.fileId + '/data', '_blank');
    popup.onload = function () {
      popup.print();
      popup.close();
    }
  };

  /**
   * Close the file preview.
   */
  $scope.closeFile = function () {
    $uibModalInstance.dismiss();
  };

  // Close the modal when the user exits this state
  var off = $transitions.onStart({}, function(transition) {
    if (!$uibModalInstance.closed) {
      if (transition.to().name === $state.current.name) {
        $uibModalInstance.close();
      } else {
        $uibModalInstance.dismiss();
      }
    }
    off();
  });

  /**
   * Return true if we can display the preview image.
   */
  $scope.canDisplayPreview = function () {
    return $scope.file && $scope.file.mimetype !== 'application/pdf';
  };
});