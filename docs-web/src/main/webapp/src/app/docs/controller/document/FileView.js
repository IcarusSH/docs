'use strict';

/**
 * File view controller.
 */
angular.module('docs').controller('FileView', function($uibModal, $state, $stateParams, $timeout) {
  var modal = $uibModal.open({
    windowClass: 'modal modal-fileview',
    // 加 ?v=2 让 angular $http 重新拉取模板，避免浏览器缓存旧版 file.view.html
    // （旧模板里水印 div 引用 watermarkBackground，没有 || $root.watermarkBackground 兜底）
    templateUrl: 'partial/docs/file.view.html?v=2',
    controller: 'FileModalView',
    size: 'lg'
  });

  // Returns to document view on file close
  modal.closed = false;
  modal.result.then(function() {
    modal.closed = true;
  }, function() {
    modal.closed = true;
    $timeout(function () {
      // After all router transitions are passed,
      // if we are still on the file route, go back to the document
      if ($state.current.name === 'document.view.content.file' || $state.current.name === 'document.default.file') {
        $state.go('^', {id: $stateParams.id});
      }
    });
  });
});