'use strict';

/**
 * Navigation controller.
 */
angular.module('docs').controller('Navigation', function($scope, $state, $stateParams, $rootScope, $http, User, Restangular) {
  User.userInfo().then(function(data) {
    $rootScope.userInfo = data;
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