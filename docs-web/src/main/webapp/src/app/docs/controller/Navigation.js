'use strict';

/**
 * Navigation controller.
 */
angular.module('docs').controller('Navigation', function($scope, $state, $stateParams, $rootScope, $http, $interval, User, Restangular) {
  User.userInfo().then(function(data) {
    $rootScope.userInfo = data;
    // 兜底：把水印背景图生成到 $rootScope，让文件预览模板的
    // `watermarkBackground || $root.watermarkBackground` 兜底生效。
    // 即便 FileModalView.js 被浏览器缓存了旧版本（没有设 $scope.watermarkBackground），
    // 这里仍然能保证水印 div 拿到一个非空 background-image。
    if (data && !data.anonymous) {
      var now = new Date();
      var pad = function (n) { return n < 10 ? '0' + n : '' + n; };
      var wmTime = now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate()) +
                   ' ' + pad(now.getHours()) + ':' + pad(now.getMinutes()) + ':' + pad(now.getSeconds());
      var wmText = data.username + ' · ' + wmTime;
      var escXml = function (s) {
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
          .replace(/"/g, '&quot;').replace(/'/g, '&apos;');
      };
      var w = 400, h = 220;
      var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="' + w + '" height="' + h + '">' +
        '<g transform="rotate(-30 ' + (w/2) + ' ' + (h/2) + ')" ' +
           'font-family="Helvetica, Arial, sans-serif" font-size="32" font-weight="700">' +
          '<text x="' + (w/2) + '" y="' + (h/2) + '" text-anchor="middle" dominant-baseline="middle" ' +
                'fill="#000" fill-opacity="0.7" ' +
                'stroke="#fff" stroke-opacity="0.9" stroke-width="1.5">' +
            escXml(wmText) +
          '</text>' +
        '</g>' +
      '</svg>';
      $rootScope.watermarkBackground = 'url("data:image/svg+xml;base64,' + btoa(svg) + '")';
    }
    if (data.anonymous) {
      if($state.current.name !== 'login') {
        $state.go('login', {
          redirectState: $state.current.name,
          redirectParams: JSON.stringify($stateParams),
        }, {
          location: 'replace'
        });
      }
    }
  });

  // Pending borrow requests targeting me as approver. Refreshed every 60s.
  $scope.pendingBorrows = [];
  function refreshPendingBorrows() {
    if (!$rootScope.userInfo || $rootScope.userInfo.anonymous || $rootScope.userInfo.username === 'guest') {
      $scope.pendingBorrows = [];
      return;
    }
    Restangular.one('borrow/pending').get().then(function(data) {
      $scope.pendingBorrows = data;
    }, function() {
      // Non-fatal: keep stale value on transient error.
    });
  }
  refreshPendingBorrows();
  var pollHandle = $interval(refreshPendingBorrows, 60000);
  $scope.$on('$destroy', function() {
    if (pollHandle) { $interval.cancel(pollHandle); }
  });

  /**
   * User logout.
   */
  $scope.logout = function($event) {
    User.logout().then(function() {
      User.userInfo(true).then(function(data) {
        $rootScope.userInfo = data;
      });
      $state.go('main');
    });
    $event.preventDefault();
  };

  /**
   * Stop impersonating (when the banner is up). Reloads so the navigation/ACL
   * state reflects the real admin again.
   */
  $scope.stopImpersonating = function($event) {
    if ($event) $event.preventDefault();
    // Use $http directly with withCredentials so we always get the auth_token cookie
    // through, and surface the response (or any error) in the console.
    $http({
      method: 'POST',
      url: '../api/admin/impersonate/stop',
      withCredentials: true,
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      transformRequest: function(data) { return ''; }
    }).then(function() {
      window.location.reload();
    }, function(resp) {
      console.error('Stop impersonation failed:', resp.status, resp.statusText, resp.data);
    });
  };
});