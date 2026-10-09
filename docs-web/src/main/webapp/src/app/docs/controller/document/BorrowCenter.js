'use strict';

/**
 * Borrow center controller (admin only).
 *
 * Lists every borrow request in the system with a status filter. Admins can revoke
 * APPROVED borrows, which immediately soft-deletes the BORROW ACL and marks the
 * request as REVOKED.
 */
angular.module('docs').controller('BorrowCenter', function($scope, $rootScope, $translate, $q, Restangular, User) {
  $scope.requests = [];
  $scope.statusFilter = '';
  $scope.loading = false;
  $scope.error = null;

  /**
   * Refetch the page from the server. Triggered on init and after each filter / action.
   * The server enforces admin via checkBaseFunction(), so a 403 response is the only
   * signal we need to surface the "forbidden" state.
   */
  function load() {
    $scope.loading = true;
    $scope.error = null;
    var params = {};
    if ($scope.statusFilter) {
      params.status = $scope.statusFilter;
    }
    Restangular.one('borrow/admin/list').get(params).then(function(data) {
      $scope.requests = data;
      $scope.loading = false;
    }, function(response) {
      $scope.loading = false;
      $scope.error = response;
    });
  }

  // Wrap User.userInfo() so a cached value (data object instead of promise) still flows
  // through our then() — User.js stores the resolved value in its closure after the first
  // call, so subsequent userInfo() returns the data directly, not a promise.
  $q.when(User.userInfo()).then(function(data) {
    if (data && !data.anonymous) {
      $rootScope.userInfo = data;
    }
    load();
  }, function() {
    $scope.error = { status: 401 };
  });

  /**
   * Apply a status filter chip.
   */
  $scope.setFilter = function(status) {
    $scope.statusFilter = ($scope.statusFilter === status) ? '' : status;
    load();
  };

  /**
   * Can the current user revoke this request? Only admin can; only on APPROVED.
   * We check the base_functions array (returned by /user) rather than hard-coding
   * the username so admin role transfers work even when the "admin" account is renamed.
   */
  $scope.isAdmin = function() {
    var info = $rootScope.userInfo;
    return info && info.base_functions && info.base_functions.indexOf('ADMIN') >= 0;
  };
  $scope.canRevoke = function(req) {
    return $scope.isAdmin() && req.status === 'APPROVED';
  };

  /**
   * Revoke an APPROVED borrow. Reload after success so the row reflects REVOKED.
   */
  $scope.revoke = function(req) {
    if (!$scope.canRevoke(req)) return;
    if (!window.confirm($translate.instant('document.borrow.confirm_revoke'))) {
      return;
    }
    Restangular.one('borrow/' + req.id + '/revoke').post('').then(function() {
      load();
    }, function(response) {
      $scope.error = response;
    });
  };
});
